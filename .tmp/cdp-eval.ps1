param(
    [int]$Port = 9223,
    [string]$Expression = "document.title"
)

$ErrorActionPreference = "Stop"
$targets = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json"
$page = $targets | Where-Object { $_.type -eq "page" } | Select-Object -First 1
if (-not $page) { throw "No page target on port $Port" }

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null

$payload = @{
    id = 1
    method = "Runtime.evaluate"
    params = @{
        expression = $Expression
        returnByValue = $true
        awaitPromise = $true
    }
} | ConvertTo-Json -Depth 6 -Compress

$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
$ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null

$buffer = New-Object byte[] 1048576
$ms = [IO.MemoryStream]::new()
do {
    $result = $ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $ms.Write($buffer, 0, $result.Count)
} while (-not $result.EndOfMessage)

$text = [Text.Encoding]::UTF8.GetString($ms.ToArray())
$ws.Dispose()

$obj = $text | ConvertFrom-Json
"URL: $($page.url)"
"TITLE: $($page.title)"
if ($null -ne $obj.result.result.value) {
    "VALUE:"
    $obj.result.result.value
} else {
    "RAW: $text"
}
