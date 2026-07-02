# GitLab 采样数据导入脚本（外网环境）
# 用途：将从内网导出的 GitLab 采样数据导入到外网测试数据库

param(
    [string]$SqlFile = "gitlab_sample_data.sql",
    [string]$Host = "localhost",
    [int]$Port = 15432,
    [string]$Database = "qaflex",
    [string]$Username = "qaflex"
)

$ErrorActionPreference = "Stop"

Write-Host "=" -NoNewline -ForegroundColor Cyan
Write-Host ("=" * 79) -ForegroundColor Cyan
Write-Host "GitLab 采样数据导入工具（外网环境）" -ForegroundColor Green
Write-Host ("=" * 80) -ForegroundColor Cyan
Write-Host ""

# 检查文件是否存在
if (-not (Test-Path $SqlFile)) {
    Write-Host "❌ 错误: 文件不存在 $SqlFile" -ForegroundColor Red
    Write-Host ""
    Write-Host "提示：如果文件是压缩的 (.sql.gz)，请先解压：" -ForegroundColor Yellow
    Write-Host "  gunzip gitlab_sample_data.sql.gz" -ForegroundColor Gray
    exit 1
}

Write-Host "📂 SQL 文件: $SqlFile" -ForegroundColor Cyan
Write-Host "🔗 目标数据库: $Host`:$Port/$Database" -ForegroundColor Cyan
Write-Host ""

# 提示用户确认
$confirm = Read-Host "⚠️  警告: 导入会覆盖现有数据。是否继续? (yes/no)"
if ($confirm -ne "yes") {
    Write-Host "❌ 用户取消操作" -ForegroundColor Yellow
    exit 0
}

Write-Host ""
Write-Host "🚀 开始导入数据..." -ForegroundColor Green
Write-Host ""

# 设置密码环境变量（避免命令行中暴露密码）
$env:PGPASSWORD = Read-Host "请输入数据库密码" -AsSecureString | ConvertFrom-SecureString -AsPlainText

try {
    # 使用 psql 导入
    $psqlArgs = @(
        "-h", $Host,
        "-p", $Port,
        "-U", $Username,
        "-d", $Database,
        "-f", $SqlFile,
        "--set", "ON_ERROR_STOP=on",
        "--quiet"
    )

    Write-Host "执行命令: psql $($psqlArgs -join ' ')" -ForegroundColor Gray
    Write-Host ""

    & psql @psqlArgs

    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "✅ 数据导入成功!" -ForegroundColor Green
        Write-Host ""
        Write-Host "📌 下一步:" -ForegroundColor Cyan
        Write-Host "  1. 验证数据: SELECT COUNT(*) FROM projects;" -ForegroundColor Gray
        Write-Host "  2. 查看议题: SELECT COUNT(*) FROM issues;" -ForegroundColor Gray
        Write-Host "  3. 查看合并请求: SELECT COUNT(*) FROM merge_requests;" -ForegroundColor Gray
        Write-Host ""
    } else {
        Write-Host ""
        Write-Host "❌ 导入失败 (退出码: $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }

} catch {
    Write-Host ""
    Write-Host "❌ 导入过程中发生错误: $_" -ForegroundColor Red
    exit 1
} finally {
    # 清理密码环境变量
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}
