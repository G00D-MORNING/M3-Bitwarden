<#
.SYNOPSIS
  Richtet das Gerätezertifikat für vault.jochenderrochen.de unter Windows ein (für Chrome und die
  M3-Bitwarden-Erweiterung).

.DESCRIPTION
  1. Erzeugt mit Windows-Bordmitteln (certreq) einen nicht exportierbaren Schlüssel im
     Benutzer-Zertifikatsspeicher und eine Zertifikatsanfrage (CSR). Die Anfrage landet in der
     Zwischenablage.
  2. Du fügst sie in Cloudflare ein (SSL/TLS -> Client-Zertifikate -> Erstellen ->
     "Eigenen privaten Schlüssel und CSR verwenden") und kopierst das ausgestellte Zertifikat.
  3. Das Skript liest das Zertifikat aus der Zwischenablage, verbindet es mit dem Schlüssel und
     sagt Chrome, dass es dieses Zertifikat für vault.* automatisch verwenden soll
     (Richtlinie AutoSelectCertificateForUrls, nur für deinen Benutzer).

  Der private Schlüssel verlässt den PC nie. Keine Administratorrechte nötig.
#>
[CmdletBinding()]
param(
  [string]$Server = "vault.jochenderrochen.de",
  [string]$DeviceName = "M3B $env:COMPUTERNAME"
)

$ErrorActionPreference = "Stop"
$work = Join-Path $env:LOCALAPPDATA "M3Bitwarden\mtls"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$inf = Join-Path $work "request.inf"
$csr = Join-Path $work "request.csr"
$crt = Join-Path $work "certificate.pem"

Write-Host "`n== Schritt 1: Schlüssel und Anfrage erzeugen ==" -ForegroundColor Cyan
@"
[Version]
Signature = "`$Windows NT`$"

[NewRequest]
Subject = "CN=$DeviceName, O=M3 Bitwarden"
KeyAlgorithm = ECDSA_P256
HashAlgorithm = sha256
Exportable = FALSE
MachineKeySet = FALSE
ProviderName = "Microsoft Software Key Storage Provider"
RequestType = PKCS10
KeyUsage = 0x80

[EnhancedKeyUsageExtension]
OID = 1.3.6.1.5.5.7.3.2
"@ | Set-Content -Path $inf -Encoding ASCII

Remove-Item $csr -ErrorAction SilentlyContinue
certreq.exe -new -q -user $inf $csr | Out-Null
if (-not (Test-Path $csr)) { throw "certreq konnte keine Anfrage erzeugen." }
Get-Content $csr -Raw | Set-Clipboard
Write-Host "Die Zertifikatsanfrage ist jetzt in der Zwischenablage." -ForegroundColor Green

Write-Host "`n== Schritt 2: In Cloudflare ausstellen ==" -ForegroundColor Cyan
Write-Host "1. Cloudflare -> jochenderrochen.de -> SSL/TLS -> Client-Zertifikate -> Zertifikat erstellen"
Write-Host "2. 'Eigenen privaten Schlüssel und CSR verwenden' wählen und die Anfrage einfügen (Strg+V)"
Write-Host "3. Gültigkeit wählen (z. B. 10 Jahre), Erstellen"
Write-Host "4. Das angezeigte ZERTIFIKAT (PEM) kopieren"
Read-Host "`nWenn das Zertifikat in der Zwischenablage ist, Enter drücken"

$pem = Get-Clipboard -Raw
if ($pem -notmatch "-----BEGIN CERTIFICATE-----") { throw "In der Zwischenablage ist kein Zertifikat (BEGIN CERTIFICATE fehlt)." }
$first = [regex]::Match($pem, "-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----").Value
Set-Content -Path $crt -Value $first -Encoding ASCII

Write-Host "`n== Schritt 3: Zertifikat installieren ==" -ForegroundColor Cyan
certreq.exe -accept -q -user $crt | Out-Null
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -like "CN=$DeviceName*" -and $_.HasPrivateKey } |
  Sort-Object NotBefore -Descending | Select-Object -First 1
if (-not $cert) { throw "Zertifikat wurde nicht mit dem Schlüssel verbunden. Stammt es aus genau dieser Anfrage?" }
Write-Host "Installiert: $($cert.Subject), gültig bis $($cert.NotAfter.ToString('d'))" -ForegroundColor Green

Write-Host "`n== Schritt 4: Chrome automatisch auswählen lassen ==" -ForegroundColor Cyan
$policy = "HKCU:\Software\Policies\Google\Chrome\AutoSelectCertificateForUrls"
New-Item -Force -Path $policy | Out-Null
$value = @{ pattern = "https://$Server"; filter = @{ SUBJECT = @{ CN = $DeviceName } } } | ConvertTo-Json -Compress -Depth 4
New-ItemProperty -Force -Path $policy -Name "1" -PropertyType String -Value $value | Out-Null
Write-Host "Richtlinie gesetzt: $value" -ForegroundColor Green

Write-Host "`nFertig. Chrome komplett schließen und neu starten, dann https://$Server/alive öffnen:"
Write-Host "Ein Datum = Zertifikat funktioniert. 'Sorry, you have been blocked' = Zertifikat fehlt."
Write-Host "Unter chrome://policy sollte AutoSelectCertificateForUrls erscheinen."
