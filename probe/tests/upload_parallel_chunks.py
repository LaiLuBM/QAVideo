#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
 TC-UPL-005  并发分片上传不丢片（同时覆盖 UPL-002 断点续传对账、UPL-003 幂等重传、DED-001 内容去重）
 优先级：P0    来源：probe/方案.md 附录B
================================================================================

【为什么这是上传链路最重要的用例】
  单文件顺序上传人工点两下就能验，但并发场景下的"原子性丢失"（两个分片同时写状态、
  后写覆盖先写）只会在特定时序暴露，一旦发生就是**静默的文件损坏**——接口全 200，
  sha256 却对不上。因此本脚本的主断言不是"接口返回 200"，而是
  **合并结果 sha256 == 源文件 sha256**（字节级等价）。

【前置条件】
  1. 后端已启动（默认 http://localhost:8080），MySQL 可用
  2. 建议 QVIDEO_REDIS_ENABLED=true（走 Redis 实现；false 时走内存实现，两者都应通过）
  3. 无需任何 AI Key：脚本用 autoParse=false，不触发解析、不产生模型费用

【输入】
  --base-url   后端地址，默认 http://localhost:8080
  --size       测试文件字节数，默认 3*1MiB+12345（≈3MB，可跨多片）
  --chunk      分片大小，默认 1MiB（刻意与前端 5MB 不同，验证服务端不写死常量）
  --workers    并发线程数，默认 8
  --seed       随机种子，默认 20261013（保证可复现）
  --timeout    单请求超时秒，默认 60
  --report     报告输出路径，默认 ./probe-report-UPL-005.json

【执行的步骤】
  A. 生成确定性随机字节 data，计算 sha256(data)
  B. POST /api/uploads/init            -> 拿 uploadId / totalChunks
  C. 先只上传前一半分片（模拟上传中断）
  D. 带 uploadId 重新 init             -> 断言 resumed=true 且 uploadedChunks 与 C 一致  (UPL-002)
  E. 8 线程**乱序并发**上传剩余分片，并**故意把第 0 片和第 N-1 片各重复上传一次**  (UPL-005 / UPL-003)
  F. GET /api/uploads/{id}             -> 断言 receivedChunks==totalChunks、progress==100
  G. POST /api/uploads/{id}/complete   -> 断言 contentHash == sha256(data)              (核心)
  H. 用同一 contentHash 再 init         -> 断言 duplicate=true 且 video.id 相同          (DED-001)
  I. 打印 PASS 汇总并写出 JSON 报告

【输出】
  stdout：每个阶段的断言结果 + 关键指标（并发耗时、分片数、contentHash）
  --report JSON：{caseId, baseUrl, size, chunk, totalChunks, workers,
                  resumedOk, receivedChunks, contentHash, expectedHash,
                  videoId, duplicatedVideoId, elapsedMs, verdict, failures[]}
  退出码：0=全部通过；1=存在断言失败（failures 非空）；2=环境不可用

【预期结果】（全部满足才算 PASS）
  1. init 返回 totalChunks == ceil(size/chunk)
  2. 重新 init 时 resumed == true，且 uploadedChunks 恰为前半段索引集合
  3. 并发+重复+乱序上传后：所有请求 2xx；receivedChunks == totalChunks；progress == 100
  4. complete 返回 contentHash == sha256(data)   ← 任一原子性缺陷都会让这条失败
  5. 二次 init 命中去重：duplicate != null、duplicate.video.id == 首次的 videoId
  6. 全流程无 5xx

【失败时的定位提示】
  - 第 3 步失败        -> Redis SET 并发写丢失（检查实现是否用了读改写而非原子集合）
  - 第 4 步 hash 不符  -> 合并顺序错位或分片覆盖写；比对分片字节数与 totalChunks
  - 第 5 步不命中      -> 唯一约束/去重键计算问题（看 DB 里 dedup_key 是否重复）
================================================================================
"""

import argparse
import hashlib
import json
import random
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

DEFAULT_BASE = "http://localhost:8080"


class ProbeFailure(AssertionError):
    """断言失败，收集到报告里而不是直接崩掉。"""


def http_json(method: str, url: str, payload=None, timeout: int = 30):
    """调用返回 {code,message,data} 的统一响应体，code!=0 视为失败。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"} if body else {}
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8") or "{}"
    parsed = json.loads(raw)
    if parsed.get("code") != 0:
        raise ProbeFailure(f"{method} {url} -> code={parsed.get('code')} message={parsed.get('message')}")
    return parsed.get("data")


def post_chunk(base: str, upload_id: str, index: int, blob: bytes, timeout: int) -> int:
    """上传单个分片；multipart/form-data，字段名固定为 file。"""
    boundary = "----QAVideoProbe" + hashlib.md5(f"{upload_id}-{index}-{time.time()}".encode()).hexdigest()
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="chunk-{index:08d}.part"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    body = head + blob + tail

    request = urllib.request.Request(
        f"{base}/api/uploads/{upload_id}/chunks/{index}",
        data=body,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8") or "{}")
    if payload.get("code") != 0:
        raise ProbeFailure(f"chunk {index} -> code={payload.get('code')} message={payload.get('message')}")
    return index


def slice_of(data: bytes, index: int, chunk: int) -> bytes:
    return data[index * chunk: min(len(data), (index + 1) * chunk)]


def run(args) -> int:
    base = args.base_url.rstrip("/")
    failures = []
    report = {
        "caseId": "UPL-005",
        "baseUrl": base,
        "size": args.size,
        "chunk": args.chunk,
        "workers": args.workers,
        "seed": args.seed,
    }

    def check(name: str, condition: bool, detail: str = ""):
        status = "PASS" if condition else "FAIL"
        print(f"  [{status}] {name}" + (f"  -> {detail}" if detail else ""))
        if not condition:
            failures.append({"step": name, "detail": detail})

    started = time.time()

    # ---- A. 确定性测试数据 ----
    data = random.Random(args.seed).randbytes(args.size)
    expected_hash = hashlib.sha256(data).hexdigest()
    total_expected = (args.size + args.chunk - 1) // args.chunk
    print(f"[UPL-005] 测试数据 {args.size} 字节, {total_expected} 片, sha256={expected_hash[:16]}...")

    # ---- B. init ----
    init = http_json("POST", f"{base}/api/uploads/init", {
        "fileName": f"probe-upl-005-{args.seed}.bin",
        "fileSize": args.size,
        "contentType": "application/octet-stream",
        "chunkSize": args.chunk,
        "autoParse": False,          # 不触发解析，避免任何模型费用
    })
    upload_id = init["uploadId"]
    total = init["totalChunks"]
    report["uploadId"] = upload_id
    report["totalChunks"] = total
    print(f"[UPL-005] uploadId={upload_id} totalChunks={total}")
    check("B. init 分片数正确", total == total_expected, f"预期 {total_expected} 实得 {total}")

    # ---- C. 只传前一半（模拟中断） ----
    half = max(1, total // 2)
    for index in range(half):
        post_chunk(base, upload_id, index, slice_of(data, index, args.chunk), args.timeout)
    print(f"[UPL-005] 已上传前半段 {half} 片，模拟中断")

    # ---- D. 重新 init 验证断点续传对账 (UPL-002) ----
    resumed = http_json("POST", f"{base}/api/uploads/init", {
        "fileName": f"probe-upl-005-{args.seed}.bin",
        "fileSize": args.size,
        "chunkSize": args.chunk,
        "uploadId": upload_id,
    })
    resumed_chunks = sorted(resumed.get("uploadedChunks") or [])
    report["resumedOk"] = bool(resumed.get("resumed"))
    check("D1. 断点续传 resumed=true", bool(resumed.get("resumed")), str(resumed.get("resumed")))
    check("D2. 已收分片与服务端记录一致",
          resumed_chunks == list(range(half)), f"预期 {list(range(half))} 实得 {resumed_chunks}")

    # ---- E. 并发 + 乱序 + 故意重复上传 (UPL-005 / UPL-003) ----
    remaining = list(range(half, total))
    plan = remaining + ([remaining[0], remaining[-1]] if remaining else [0, 0])
    random.Random(args.seed + 1).shuffle(plan)
    print(f"[UPL-005] 并发上传 {len(plan)} 次请求（含重复片），workers={args.workers}")

    concurrent_started = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(post_chunk, base, upload_id, i, slice_of(data, i, args.chunk), args.timeout)
                   for i in plan]
        for future in futures:
            future.result()          # 任一异常在此抛出
    concurrent_elapsed = int((time.time() - concurrent_started) * 1000)
    report["concurrentElapsedMs"] = concurrent_elapsed

    # ---- F. 会话状态核对 ----
    session = http_json("GET", f"{base}/api/uploads/{upload_id}")
    received = session.get("receivedChunks")
    report["receivedChunks"] = received
    report["progress"] = session.get("progress")
    check("F1. 服务端收齐全部分片", received == total, f"预期 {total} 实得 {received}")
    check("F2. 进度 100%", session.get("progress") == 100, str(session.get("progress")))
    check("F3. 分片索引集合完整",
          sorted(session.get("uploadedChunks") or []) == list(range(total)),
          str(session.get("uploadedChunks")))

    # ---- G. 合并 + 字节级校验（核心断言） ----
    completed = http_json("POST", f"{base}/api/uploads/{upload_id}/complete", timeout=args.timeout)
    actual_hash = completed.get("contentHash")
    video = (completed.get("result") or {}).get("video") or {}
    report["contentHash"] = actual_hash
    report["expectedHash"] = expected_hash
    report["videoId"] = video.get("id")
    check("G. 合并结果 sha256 与源文件一致（无丢片/错位/覆盖）",
          actual_hash == expected_hash, f"预期 {expected_hash[:16]} 实得 {str(actual_hash)[:16]}")

    # ---- H. 内容去重 (DED-001) ----
    duplicate = http_json("POST", f"{base}/api/uploads/init", {
        "fileName": "probe-upl-005-again.bin",
        "fileSize": args.size,
        "chunkSize": args.chunk,
        "contentHash": expected_hash,
    })
    dup_video = (duplicate.get("duplicate") or {}).get("video") or {}
    report["duplicatedVideoId"] = dup_video.get("id")
    check("H1. 同内容再上传被去重短路", duplicate.get("duplicate") is not None, str(duplicate.get("duplicate") is not None))
    check("H2. 命中同一条视频记录", dup_video.get("id") == video.get("id"),
          f"首次 {video.get('id')} 二次 {dup_video.get('id')}")

    report["elapsedMs"] = int((time.time() - started) * 1000)
    report["failures"] = failures
    report["verdict"] = "PASS" if not failures else "FAIL"

    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)

    print(f"\n[UPL-005] {report['verdict']}  用时 {report['elapsedMs']}ms  "
          f"并发段 {concurrent_elapsed}ms  报告: {args.report}")
    if failures:
        for item in failures:
            print(f"  !! {item['step']}: {item['detail']}")
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="TC-UPL-005 并发分片上传不丢片")
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--size", type=int, default=3 * 1024 * 1024 + 12345)
    parser.add_argument("--chunk", type=int, default=1024 * 1024)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=20261013)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--report", default="probe-report-UPL-005.json")
    args = parser.parse_args()

    try:
        return run(args)
    except ProbeFailure as failure:
        print(f"[UPL-005] 用例执行中中断: {failure}")
        return 1
    except urllib.error.URLError as error:
        print(f"[UPL-005] 环境不可用（后端未启动？）: {error}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
