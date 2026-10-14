#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
================================================================================
 TC-DDL-002  Hibernate 不得修改表结构（同时覆盖 DDL-001 LOB 列类型契约）
 优先级：P0    来源：probe/方案.md 附录B（真实事故 A2 的回归）
================================================================================

【为什么这是数据层最重要的用例】
  真实事故：ORM 把 LOB 列渲染成 tinytext(255 字节)，并且 ddl-auto=update 会主动执行
  ALTER 把初始化脚本建好的 longtext **改小**，于是解析结果写库时报
  "Data truncation: Data too long for column 'highlights'"，而接口仍返回成功——
  这是**静默的数据丢失**，最隐蔽、也最难在生产上追溯。
  该缺陷用肉眼几乎发现不了：表存在、查询正常、只有超长内容才炸。
  因此这里用**双判据**把它钉死：
     判据一：LOB 列的 COLUMN_TYPE 必须是 longtext / text（禁止 tinytext）
     判据二：应用启动前后，列类型与表 CREATE_TIME 必须**完全不变**
             （ALTER ... MODIFY 会重建表并使 CREATE_TIME 变化，因此这是"ORM 是否动过表"的铁证）

【前置条件】
  1. MySQL 中有 qavideo 库（应用至少成功启动过一次，表已存在）
  2. 能执行 mysql 命令：默认走 docker exec qavideo-mysql；
     本机装了 mysql 客户端时可用 --mysql-cmd 覆盖
  3. 允许重启一次后端（本用例需要"启动前/启动后"两次快照）

【输入】
  --container      docker 容器名，默认 qavideo-mysql
  --database       库名，默认 qavideo
  --db-user/--db-password   默认 root/root
  --mysql-cmd      （可选）自定义命令前缀，例如
                     "C:\\mysql\\bin\\mysql.exe -uroot -proot -N -B qavideo -e"
  --health-url     后端健康检查地址，默认 http://localhost:8080/api/system/health
  --restart-cmd    （可选）重启后端的 shell 命令；不提供则进入交互等待（按回车继续）
  --health-wait    等待后端重新可用的最长秒数，默认 90
  --report         报告输出路径，默认 ./probe-report-DDL-002.json

【执行的步骤】
  1. 快照 A：读 information_schema.COLUMNS（列名/类型/是否可空）
             与 information_schema.TABLES（CREATE_TIME / UPDATE_TIME）
  2. 断言 A：LOB 列类型符合契约，且不存在 tinytext
  3. 重启后端（--restart-cmd 或人工），等待 /api/system/health 返回 UP
  4. 快照 B：同 1
  5. 断言 B：
       · 所有列类型与快照 A 完全相同        → ORM 未做 ALTER
       · 表 CREATE_TIME 与快照 A 完全相同    → 未发生重建型 ALTER
  6. 输出差异明细与结论

【输出】
  stdout：列类型契约表 + 前后差异 + 结论
  --report JSON：{caseId, contract{ok, violations[]}, drift{columns[], createTime[]},
                  before, after, verdict, failures[]}
  退出码：0=通过；1=契约违反或发生漂移；2=环境不可用

【预期结果】（全部满足才算 PASS）
  1. video.summary / transcript / highlights / chapters  == LONGTEXT
     video.keywords == varchar(512)   （非 LOB，作为对照）
     parse_task.error_message == TEXT
  2. 库中**不存在**任何 tinytext 列
  3. 重启前后列类型零差异
  4. 重启前后 CREATE_TIME 零差异（若驱动返回 NULL 则该子项记 SKIP 并在报告中说明）

【失败时的定位提示】
  · 契约违反（出现 tinytext） → ORM 类型映射问题；同时检查 ddl-auto 是否被改回 update
  · 列类型漂移              → 说明启动时执行了 ALTER；检查 spring.jpa.hibernate.ddl-auto
  · 仅 CREATE_TIME 变化     → 典型"类型被改但结果恰好相同"的重建，属于高危信号
================================================================================
"""

import argparse
import json
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request

LOB_CONTRACT = {
    ("video", "summary"): "longtext",
    ("video", "transcript"): "longtext",
    ("video", "highlights"): "longtext",
    ("video", "chapters"): "longtext",
    ("parse_task", "error_message"): "text",
}
CONTROL_CONTRACT = {
    ("video", "keywords"): "varchar(512)",
}
TABLES = ("video", "parse_task")

COLUMNS_SQL = """
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = '{db}' AND TABLE_NAME IN ('video','parse_task')
ORDER BY TABLE_NAME, ORDINAL_POSITION;
"""

TABLES_SQL = """
SELECT TABLE_NAME, CREATE_TIME, UPDATE_TIME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = '{db}' AND TABLE_NAME IN ('video','parse_task')
ORDER BY TABLE_NAME;
"""


def run_sql(args, sql: str) -> str:
    if args.mysql_cmd:
        command = shlex.split(args.mysql_cmd) + [sql]
    else:
        command = ["docker", "exec", "-i", args.container, "mysql",
                   f"-u{args.db_user}", f"-p{args.db_password}", "-N", "-B", args.database, "-e", sql]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if completed.returncode != 0:
        raise RuntimeError(f"执行 SQL 失败: {completed.stderr.strip() or completed.stdout.strip()}")
    return completed.stdout.strip()


def snapshot(args) -> dict:
    columns = {}
    for line in run_sql(args, COLUMNS_SQL.format(db=args.database)).splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            columns[f"{parts[0]}.{parts[1]}"] = {"type": parts[2].lower(), "nullable": parts[3]}
    tables = {}
    for line in run_sql(args, TABLES_SQL.format(db=args.database)).splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            tables[parts[0]] = {"create_time": parts[1], "update_time": parts[2]}
    return {"columns": columns, "tables": tables}


def wait_health(url: str, seconds: int) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                payload = json.loads(response.read().decode("utf-8") or "{}")
            if (payload.get("data") or {}).get("status") == "UP":
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def run(args) -> int:
    failures = []
    report = {"caseId": "DDL-002", "database": args.database}

    def check(name: str, condition: bool, detail: str = ""):
        print(f"  [{'PASS' if condition else 'FAIL'}] {name}" + (f"  -> {detail}" if detail else ""))
        if not condition:
            failures.append({"step": name, "detail": detail})

    # ---- 1. 快照 A ----
    print("[DDL-002] 采集启动前快照（列类型 + 表创建时间）...")
    before = snapshot(args)
    report["before"] = before

    # ---- 2. LOB 列类型契约 ----
    violations = []
    print("  列类型契约：")
    for (table, column), expected in {**LOB_CONTRACT, **CONTROL_CONTRACT}.items():
        actual = (before["columns"].get(f"{table}.{column}") or {}).get("type", "<缺失>")
        ok = actual == expected
        print(f"    {'OK  ' if ok else 'BAD '} {table}.{column} = {actual}  (期望 {expected})")
        if not ok:
            violations.append({"column": f"{table}.{column}", "expected": expected, "actual": actual})
    report["contract"] = {"ok": not violations, "violations": violations}
    check("1. LOB/对照列类型符合契约", not violations, str(violations))

    tiny = [name for name, meta in before["columns"].items() if "tinytext" in meta["type"]]
    check("2. 库中不存在 tinytext 列（255 字节上限）", not tiny, str(tiny))

    # ---- 3. 重启后端 ----
    if args.restart_cmd:
        print(f"[DDL-002] 执行重启命令: {args.restart_cmd}")
        subprocess.run(args.restart_cmd, shell=True, check=False)
    else:
        input("[DDL-002] 请现在重启后端，然后按回车继续...")
    print(f"[DDL-002] 等待后端恢复: {args.health_url}")
    healthy = wait_health(args.health_url, args.health_wait)
    check("3. 重启后后端恢复可用", healthy, args.health_url)

    # ---- 4. 快照 B ----
    print("[DDL-002] 采集启动后快照...")
    after = snapshot(args)
    report["after"] = after

    # ---- 5. 漂移比对 ----
    column_drift = []
    for name, meta in before["columns"].items():
        current = after["columns"].get(name)
        if current is None:
            column_drift.append({"column": name, "change": "列消失"})
        elif current["type"] != meta["type"]:
            column_drift.append({"column": name, "from": meta["type"], "to": current["type"]})
    for name in after["columns"]:
        if name not in before["columns"]:
            column_drift.append({"column": name, "change": "新增列"})
    report["drift"] = {"columns": column_drift}
    check("4. 启动前后列类型零漂移（ORM 未执行 ALTER）", not column_drift, str(column_drift))

    create_drift = []
    comparable = False
    for table in TABLES:
        old = (before["tables"].get(table) or {}).get("create_time")
        new = (after["tables"].get(table) or {}).get("create_time")
        if old in (None, "NULL") or new in (None, "NULL"):
            continue
        comparable = True
        if old != new:
            create_drift.append({"table": table, "from": old, "to": new})
    report["drift"]["createTime"] = create_drift
    if comparable:
        check("5. 表 CREATE_TIME 未变化（未发生重建型 ALTER）", not create_drift, str(create_drift))
    else:
        print("  [SKIP] 5. 驱动未返回 CREATE_TIME，跳过重建检测")

    report["verdict"] = "PASS" if not failures else "FAIL"
    report["failures"] = failures
    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)

    print(f"\n[DDL-002] {report['verdict']}  报告: {args.report}")
    for item in failures:
        print(f"  !! {item['step']}: {item['detail']}")
    return 0 if not failures else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="TC-DDL-002 ORM 不得修改表结构")
    parser.add_argument("--container", default="qavideo-mysql")
    parser.add_argument("--database", default="qavideo")
    parser.add_argument("--db-user", default="root")
    parser.add_argument("--db-password", default="root")
    parser.add_argument("--mysql-cmd", default="")
    parser.add_argument("--health-url", default="http://localhost:8080/api/system/health")
    parser.add_argument("--restart-cmd", default="")
    parser.add_argument("--health-wait", type=int, default=90)
    parser.add_argument("--report", default="probe-report-DDL-002.json")
    args = parser.parse_args()

    try:
        return run(args)
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"[DDL-002] 环境不可用: {error}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
