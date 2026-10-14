#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
 TC-MQ-003  中间件(RocketMQ)不可用时的降级：任务不得悬挂 PENDING，服务必须存活
 优先级：P0    来源：probe/方案.md 附录B（真实事故 A4 的回归）
================================================================================

【为什么这是异步链路最重要的用例】
  MQ 的价值是把耗时工作挪出请求线程；但"中间件挂了"才是常态故障。
  此时用户最不能接受两种结果：
    (1) 任务永远停在 PENDING —— 页面一直转圈，没有尽头；
    (2) 整个应用跟着 MQ 一起起不来 / 崩掉 —— 一个小依赖拖垮全域。
  这两条都属于"用户什么都做不了"的 P0 体验事故，而且只在故障态出现，
  正常回归永远跑不到。所以本用例专门打故障态。

【前置条件】
  1. 后端以 QVIDEO_MQ_ENABLED=true 启动，且 **broker 不可达**
     （停掉容器：docker stop qavideo-rmqbroker；或把 ROCKETMQ_NAME_SERVER 指到空端口）
  2. 数据库可用（MySQL）
  3. 不需要 AI Key：任务会在投递阶段就失败，不会走到模型调用

【输入】
  --base-url       后端地址，默认 http://localhost:8080
  --url            用于导入的视频链接，默认 https://example.com/probe-mq-degrade
  --import-budget  导入接口响应时间上限(ms)，默认 8000   ← 超过即判失败（不得卡住请求线程）
  --task-timeout   任务收敛为终态的最长等待秒，默认 45    ← 超时仍 PENDING 即判失败
  --recover-cmd    （可选）恢复 broker 的 shell 命令，例如
                     "docker start qavideo-rmqbroker"
                   提供后会额外验证"故障恢复后可重新解析成功"
  --recover-wait   恢复后等待 broker 就绪的秒数，默认 30
  --report         报告输出路径，默认 ./probe-report-MQ-003.json

【执行的步骤】
  1. GET /api/system/status  -> 断言 mqTransport=="rocketmq" 且 mqConsumerConnected==false
  2. GET /api/system/health  -> 断言 UP（应用未被中间件拖死）        ← 事故 A4 核心
  3. POST /api/videos/import-url 并计时
  4. 断言导入耗时 <= import-budget 且返回 task.id
  5. 轮询 GET /api/tasks/{id}，断言在 task-timeout 内进入终态
       · 终态必须是 FAILED（不能是 PENDING 悬挂，也不应是 SUCCESS）
  6. 断言 task.errorMessage 明确指出投递失败且含 "RocketMQ"
  7. GET /api/videos/{id}    -> 断言 video.status == FAILED（状态与任务一致）
  8. 再次 GET /api/system/health -> 断言仍 UP（服务未崩、未进入僵尸态）
  9.（可选，--recover-cmd）恢复 broker -> 等待 -> POST /api/videos/{id}/parse
       断言新任务最终 SUCCESS（可恢复性，避免"一次故障永久不可用"）

【输出】
  stdout：逐条断言 + 关键指标（导入耗时、任务终态耗时、错误信息）
  --report JSON：{caseId, mqEnabled, consumerConnected, healthBefore, importMs,
                  taskId, taskStatus, taskElapsedSec, errorMessage, videoStatus,
                  healthAfter, recovered, verdict, failures[]}
  退出码：0=全部通过；1=存在断言失败；2=环境不可用

【预期结果】（全部满足才算 PASS）
  1. status.mqTransport == "rocketmq"，mqConsumerConnected == false（确实处于故障态）
  2. health == UP                          ← 应用必须活着
  3. 导入接口在 8s 内返回（不得因为内部重试把请求线程占满）
  4. 任务在 45s 内变成 FAILED，**不得长期停留在 PENDING**
  5. errorMessage 包含 "RocketMQ"，人可读懂、可直接定位
  6. video.status == FAILED
  7. 故障期间 health 仍为 UP
  8.（可选）恢复 broker 后重新解析可成功，无需重启应用

【本用例防的 3 类真实缺陷】
  · 应用启动期因 MQ 不可用直接失败（应后台重连，不得拖垮启动）
  · 投递失败未把任务标记为失败，导致 PENDING 悬挂
  · 投递重试无上限，把 HTTP 请求线程长时间占住（表现为导入接口几十秒不返回）
================================================================================
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE = "http://localhost:8080"
PENDING = {"PENDING", "RUNNING"}
TERMINAL = {"SUCCESS", "FAILED"}


def http_json(method: str, url: str, payload=None, timeout: int = 30):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"} if body else {}
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        parsed = json.loads(response.read().decode("utf-8") or "{}")
    if parsed.get("code") != 0:
        raise AssertionError(f"{method} {url} -> code={parsed.get('code')} message={parsed.get('message')}")
    return parsed.get("data")


def run(args) -> int:
    base = args.base_url.rstrip("/")
    failures = []
    report = {"caseId": "MQ-003", "baseUrl": base}

    def check(name: str, condition: bool, detail: str = ""):
        print(f"  [{'PASS' if condition else 'FAIL'}] {name}" + (f"  -> {detail}" if detail else ""))
        if not condition:
            failures.append({"step": name, "detail": detail})

    # ---- 1. 确认确实处于"Mq 开启但 broker 不可达"的故障态 ----
    status = http_json("GET", f"{base}/api/system/status")
    extras = status.get("extras") or {}
    report["mqEnabled"] = status.get("rocketMqEnabled")
    report["consumerConnected"] = extras.get("mqConsumerConnected")
    report["transport"] = extras.get("mqTransport")
    check("1a. MQ 已启用（transport=rocketmq）", extras.get("mqTransport") == "rocketmq",
          str(extras.get("mqTransport")))
    check("1b. 消费者未连接（确认处于故障态）", extras.get("mqConsumerConnected") is False,
          str(extras.get("mqConsumerConnected")))

    # ---- 2. 应用必须存活（事故 A4 核心断言） ----
    health = http_json("GET", f"{base}/api/system/health")
    report["healthBefore"] = health.get("status")
    check("2. 中间件不可用时应用仍为 UP（不因 MQ 拖垮启动/运行）",
          health.get("status") == "UP", str(health.get("status")))

    # ---- 3~4. 导入接口必须在预算内返回 ----
    started = time.time()
    imported = http_json("POST", f"{base}/api/videos/import-url",
                         {"url": args.url, "title": "probe-mq-degrade", "autoParse": True},
                         timeout=max(30, args.import_budget // 1000 + 20))
    import_ms = int((time.time() - started) * 1000)
    task_id = (imported.get("task") or {}).get("id")
    video_id = (imported.get("video") or {}).get("id")
    report.update({"importMs": import_ms, "taskId": task_id, "videoId": video_id})
    check("3. 导入接口在预算内返回（未把请求线程卡在重试上）",
          import_ms <= args.import_budget, f"{import_ms}ms > 预算 {args.import_budget}ms")
    check("4. 返回了任务号", task_id is not None, str(task_id))
    if task_id is None:
        return finish(report, failures, args)

    # ---- 5~6. 任务必须收敛为 FAILED，不得悬挂 PENDING ----
    deadline = time.time() + args.task_timeout
    task = {}
    while time.time() < deadline:
        task = http_json("GET", f"{base}/api/tasks/{task_id}")
        if task.get("status") in TERMINAL:
            break
        time.sleep(1)
    task_elapsed = args.task_timeout - max(0, int(deadline - time.time()))
    task_status = task.get("status")
    report.update({"taskStatus": task_status, "taskElapsedSec": task_elapsed,
                   "errorMessage": task.get("errorMessage")})
    check("5a. 任务进入终态而非悬挂 PENDING", task_status in TERMINAL, f"status={task_status}")
    check("5b. 终态为 FAILED（投递失败必须显式失败，不能假成功）",
          task_status == "FAILED", f"status={task_status}")
    check("6. 错误信息明确指出 RocketMQ 投递失败",
          "RocketMQ" in str(task.get("errorMessage")), str(task.get("errorMessage")))

    # ---- 7. 视频状态与任务一致 ----
    video = http_json("GET", f"{base}/api/videos/{video_id}")
    report["videoStatus"] = video.get("status")
    check("7. video.status 与任务一致（FAILED）", video.get("status") == "FAILED", str(video.get("status")))

    # ---- 8. 服务仍然存活 ----
    health_after = http_json("GET", f"{base}/api/system/health")
    report["healthAfter"] = health_after.get("status")
    check("8. 故障期间服务保持可用（health 仍 UP）", health_after.get("status") == "UP",
          str(health_after.get("status")))

    # ---- 9. 可选：恢复后可重新解析（不重启应用） ----
    if args.recover_cmd:
        print(f"[MQ-003] 执行恢复命令: {args.recover_cmd}")
        subprocess.run(args.recover_cmd, shell=True, check=False)
        recovered = False
        for _ in range(args.recover_wait):
            time.sleep(1)
            current = http_json("GET", f"{base}/api/system/status")
            if (current.get("extras") or {}).get("mqConsumerConnected") is True:
                recovered = True
                break
        report["consumerReconnected"] = recovered
        check("9a. broker 恢复后消费者自动重连（无需重启应用）", recovered, str(recovered))

        if recovered:
            new_task = http_json("POST", f"{base}/api/videos/{video_id}/parse")
            new_id = (new_task.get("task") or {}).get("id")
            deadline = time.time() + args.task_timeout
            final = {}
            while time.time() < deadline:
                final = http_json("GET", f"{base}/api/tasks/{new_id}")
                if final.get("status") in TERMINAL:
                    break
                time.sleep(1)
            report["recoveredTaskStatus"] = final.get("status")
            check("9b. 恢复后重新解析成功（故障可自愈）", final.get("status") == "SUCCESS",
                  str(final.get("status")))

    return finish(report, failures, args)


def finish(report: dict, failures: list, args) -> int:
    report["failures"] = failures
    report["verdict"] = "PASS" if not failures else "FAIL"
    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(f"\n[MQ-003] {report['verdict']}  导入耗时 {report.get('importMs')}ms  "
          f"任务终态 {report.get('taskStatus')}  报告: {args.report}")
    for item in failures:
        print(f"  !! {item['step']}: {item['detail']}")
    return 0 if not failures else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="TC-MQ-003 中间件不可用时的降级行为")
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--url", default="https://example.com/probe-mq-degrade")
    parser.add_argument("--import-budget", type=int, default=8000)
    parser.add_argument("--task-timeout", type=int, default=45)
    parser.add_argument("--recover-cmd", default="")
    parser.add_argument("--recover-wait", type=int, default=30)
    parser.add_argument("--report", default="probe-report-MQ-003.json")
    args = parser.parse_args()

    try:
        return run(args)
    except urllib.error.URLError as error:
        print(f"[MQ-003] 环境不可用（后端未启动？）: {error}")
        return 2
    except AssertionError as error:
        print(f"[MQ-003] 用例执行中中断: {error}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
