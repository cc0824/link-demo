param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [string]$OutputPath = (Join-Path $PSScriptRoot '../docs/prompts/conversation.md')
)

# Export visible messages only; never export reasoning items or opaque tool payloads.
$stream = [System.IO.FileStream]::new($SourcePath, [System.IO.FileMode]::Open,
    [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
$reader = [System.IO.StreamReader]::new($stream)
$document = [System.Text.StringBuilder]::new()
$null = $document.AppendLine('# 项目对话记录（公开消息）')
$null = $document.AppendLine('导出范围：本会话用户消息与助手公开回复，按原始顺序保留，仅规范化消息末尾空白。排除系统指令、隐藏推理、自动压缩摘要、环境注入及不透明工具载荷；不以事后摘要替代对话。')
$null = $document.AppendLine("导出时间（UTC）：$([DateTimeOffset]::UtcNow.ToString('o'))。本文件是该时间点的快照，后续对话需再次导出。")
$null = $document.AppendLine('用户截图另存于 attachments/；原消息中的临时路径保留原样，映射见 attachments/README.md。验证日志见 ../evidence/。')
$count = 0
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        try { $event = $line | ConvertFrom-Json } catch { continue }
        if ($event.type -ne 'response_item' -or $event.payload.type -ne 'message') { continue }
        $message = $event.payload
        if ($message.role -notin @('user', 'assistant')) { continue }
        if ($message.channel -in @('analysis', 'summary') -or $message.phase -in @('analysis', 'summary')) { continue }
        $body = ($message.content | Where-Object { $_.type -in @('input_text', 'output_text', 'text') } |
            ForEach-Object { $_.text }) -join "`n"
        if ([string]::IsNullOrWhiteSpace($body)) { continue }
        $trimmed = $body.TrimStart()
        if ($trimmed.StartsWith('<environment_context>') -or
            $trimmed.StartsWith('<external_browser_tabs>') -or
            $trimmed.StartsWith('Another language model started') -or
            $trimmed.StartsWith('## Active task')) { continue }
        $count++
        $timestamp = ([DateTimeOffset]$event.timestamp).ToUniversalTime().ToString('o')
        $null = $document.AppendLine("`n## $count — $($message.role) — $timestamp`n")
        $null = $document.AppendLine($body.TrimEnd())
    }
} finally { $reader.Dispose() }
if ($count -eq 0) { throw 'No visible messages found; existing export was not overwritten.' }
[System.IO.File]::WriteAllText([System.IO.Path]::GetFullPath($OutputPath),
    $document.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Output "Exported $count visible messages."
