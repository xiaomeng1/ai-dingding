#!/bin/bash
# ============================================================
# 钉钉机器人系统启动脚本（后台运行 + 日志记录）
# 使用前请修改下方参数为实际值
# ============================================================

# 钉钉应用凭证（在钉钉开放平台获取）
DING_APP_KEY="dingdjinfi94nshyrw2i"
DING_APP_SECRET="TvPjo5pkxXU3tzlD1TmXiV2gNX4lzgMip5LzysOFn-x86Z2luSQKHjhhT-0Rzkkv"

# 系统登录凭证（os.zhida-keji.com.cn 账号）
SYS_USERNAME="19513764334"
SYS_PASSWORD="zdlb"

# JAR 包路径（与本脚本放在同一目录）
JAR_FILE="ai-dingding-0.0.1-SNAPSHOT.jar"

# 日志文件路径（自动按日期命名）
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/app-$(date +%Y%m%d).log"

# PID 文件（用于查询和停止进程）
PID_FILE="app.pid"

# ============================================================

if [ ! -f "$JAR_FILE" ]; then
    echo "[ERROR] 找不到 JAR 文件：$JAR_FILE"
    echo "请将打包好的 JAR 文件放在与本脚本相同的目录下"
    exit 1
fi

# 检查是否已有进程在运行
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[WARN] 程序已在运行，PID=$OLD_PID，请先执行 stop.sh 停止后再启动"
        exit 1
    else
        echo "[INFO] 清理过期 PID 文件"
        rm -f "$PID_FILE"
    fi
fi

# 创建日志目录
mkdir -p "$LOG_DIR"

echo "[INFO] 正在后台启动钉钉机器人系统..."
echo "[INFO] JAR:  $JAR_FILE"
echo "[INFO] 日志：$LOG_FILE"

nohup java \
  -Dding.appKey="$DING_APP_KEY" \
  -Dding.appSecret="$DING_APP_SECRET" \
  -Dsys.username="$SYS_USERNAME" \
  -Dsys.password="$SYS_PASSWORD" \
  -jar "$JAR_FILE" \
  >> "$LOG_FILE" 2>&1 &

# 保存 PID
echo $! > "$PID_FILE"
echo "[INFO] 启动成功，PID=$(cat $PID_FILE)"
echo "[INFO] 查看日志：tail -f $LOG_FILE"
echo "[INFO] 停止程序：bash stop.sh"
