#!/bin/bash
# ============================================================
# 钉钉机器人系统停止脚本
# ============================================================

PID_FILE="app.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "[WARN] 未找到 PID 文件，程序可能未在运行"
    exit 0
fi

PID=$(cat "$PID_FILE")

if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    rm -f "$PID_FILE"
    echo "[INFO] 程序已停止，PID=$PID"
else
    echo "[WARN] 进程 $PID 不存在，清理 PID 文件"
    rm -f "$PID_FILE"
fi
