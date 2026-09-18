param([string]$path)
$xml = Get-Content $path -Raw
[regex]::Matches($xml, 'text="([^"]+)"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"') | ForEach-Object {
    "{0} => {1}" -f $_.Groups[1].Value, $_.Groups[2].Value
}
