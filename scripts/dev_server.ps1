param(
    [int]$Port = 8000,
    [string]$Root = (Get-Location).Path
)

$listener = New-Object System.Net.HttpListener
$prefix = "http://localhost:$Port/"
$listener.Prefixes.Add($prefix)
$listener.Start()
Write-Host "Serving $Root at $prefix"

while ($true) {
    $context = $listener.GetContext()
    $request = $context.Request
    $path = $request.Url.AbsolutePath.TrimStart('/')
    if ([string]::IsNullOrWhiteSpace($path)) { $path = 'index.html' }
    $fullPath = Join-Path $Root $path

    if (Test-Path $fullPath) {
        try {
            $bytes = [System.IO.File]::ReadAllBytes($fullPath)
            $context.Response.StatusCode = 200
            $ext = [System.IO.Path]::GetExtension($fullPath).ToLowerInvariant()
            switch ($ext) {
                '.xml' { $context.Response.ContentType = 'application/xml' }
                '.html' { $context.Response.ContentType = 'text/html' }
                '.json' { $context.Response.ContentType = 'application/json' }
                default { $context.Response.ContentType = 'text/plain' }
            }
            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        } catch {
            $context.Response.StatusCode = 500
            $msg = [Text.Encoding]::UTF8.GetBytes("Server error")
            $context.Response.OutputStream.Write($msg, 0, $msg.Length)
        }
    } else {
        $context.Response.StatusCode = 404
        $msg = [Text.Encoding]::UTF8.GetBytes("Not Found")
        $context.Response.OutputStream.Write($msg, 0, $msg.Length)
    }
    $context.Response.OutputStream.Close()
}
