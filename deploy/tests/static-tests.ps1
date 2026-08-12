param(
    [string]$DeployRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$script:Failures = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if ($Condition) {
        Write-Host "PASS: $Message"
    } else {
        Write-Host "FAIL: $Message" -ForegroundColor Red
        $script:Failures++
    }
}

function Read-DeployFile {
    param([string]$RelativePath)
    return [IO.File]::ReadAllText((Join-Path $DeployRoot $RelativePath), [Text.Encoding]::UTF8)
}

$example = Read-DeployFile 'deploy.env.example'
$credentials = Read-DeployFile 'init-credentials.sh'
$setup = Read-DeployFile '03-system-setup.sh'
$jdkInstall = Read-DeployFile '02-install-jdk.sh'
$platformConfig = Read-DeployFile 'application.yml'
$consoleConfig = Read-DeployFile 'jt-console-application.yml'
$firewall = Read-DeployFile '04-firewall-start.sh'
$nginx = Read-DeployFile 'nginx-jt-console.conf'
$consoleUnit = Read-DeployFile 'jt-console.service'
$platformUnit = Read-DeployFile 'jt-platform.service'
$release = Read-DeployFile '05-deploy-console.sh'
$ready = Read-DeployFile 'wait-console-ready.sh'
$tls = Read-DeployFile '06-enable-https.sh'
$verification = Read-DeployFile '07-verify-deployment.sh'

Assert-True ($example -match '(?m)^PUBLIC_HOST=') 'environment sample parameterizes the public host'
Assert-True ($example -match '(?m)^MEDIA_REACHABLE_ADDRESS=') 'environment sample parameterizes the media address'
Assert-True ($example -match '(?m)^JDK_HOME=') 'environment sample parameterizes JDK_HOME'
Assert-True ($example -match '(?m)^JT_CONSOLE_JAR_SHA256=$') 'artifact digests have no usable default'
Assert-True ($credentials -match 'openssl rand -hex 32') 'ingest key uses at least 256 bits of randomness'
Assert-True ($credentials -match 'htpasswd -inBC') 'administrator password is converted to BCrypt from stdin'
Assert-True ($credentials -match 'existing console and platform ingest keys differ') 'credential conflicts fail closed'
Assert-True ($credentials -match 'existing_admin_username') 'repeated initialization preserves the administrator identity'
Assert-True ($credentials -match 'chmod 0640') 'service environment files are restricted to 0640'
Assert-True ($credentials -notmatch 'echo .*\$ingest_key|echo .*\$admin_password') 'credential values are not echoed'
Assert-True ($setup -match 'useradd --system --shell /usr/sbin/nologin') 'services use non-login system accounts'
Assert-True ($setup -match '/etc/jt-platform' -and $setup -match '/etc/jt-console') 'service configuration directories are separate'
Assert-True ($jdkInstall.IndexOf('verify_sha256') -lt $jdkInstall.IndexOf('tar --extract')) 'JDK digest is verified before extraction'
Assert-True ($jdkInstall -notmatch '/opt/jdk-[0-9]') 'JDK installer has no version-specific fixed path'
Assert-True ($platformConfig -match '(?ms)^server:\s+address: 127\.0\.0\.1') 'gateway HTTP binds to loopback'
Assert-True ($platformConfig -match '(?ms)management:\s+server:\s+address: 127\.0\.0\.1') 'gateway management binds to loopback'
Assert-True ($platformConfig -match '\$\{JT_MEDIA_REACHABLE_ADDRESS\}') 'media reachable address comes from runtime environment'
Assert-True ($platformConfig -match 'X-JT-Ingest-Key: "\$\{JT_PLATFORM_INGEST_KEY\}"') 'gateway delivery reads the generated ingest key'
Assert-True ($consoleConfig -match '(?ms)^server:\s+address: 127\.0\.0\.1') 'console binds to loopback'
Assert-True ($consoleConfig -match '(?m)^\s+forward-headers-strategy:\s+native\s*$') 'console uses native forwarded-header handling'
Assert-True ($consoleConfig -match '(?m)^\s+internal-proxies:\s+"127\.0\.0\.1/32, ::1/128"\s*$') 'console trusts only the loopback reverse proxy'
Assert-True ($firewall -match 'EXTERNAL_FIREWALL_CONFIRMED.*true') 'external firewall mode requires explicit confirmation'
Assert-True ($firewall -match 'ufw allow 7811:7814/tcp') 'only JT/T 1078 device ingress media ports are allowed'
Assert-True ($firewall -notmatch 'ufw allow (7810|7815|8100|8109|8300)/tcp') 'private ports are not added to the firewall allow list'
foreach ($privatePath in @('internal', 'device', 'actuator', 'ingest')) {
    Assert-True ($nginx -match "location \^~ /$privatePath/") "Nginx rejects /$privatePath/"
}
Assert-True ($nginx -match 'proxy_pass http://127\.0\.0\.1:8300/api/') 'browser API uses the loopback console proxy'
Assert-True ($nginx -match 'proxy_pass http://127\.0\.0\.1:7815/ws') 'browser media uses the loopback WSS proxy'
Assert-True (([regex]::Matches($nginx, 'proxy_set_header X-Forwarded-For \$remote_addr;')).Count -eq 2 -and $nginx -notmatch '\$proxy_add_x_forwarded_for') 'Nginx replaces untrusted forwarded-for values for console routes'
Assert-True ($platformUnit -match '(?m)^Requires=jt-console\.service$') 'platform requires the console service'
Assert-True ($platformUnit -match '(?m)^After=jt-console\.service$') 'platform starts after the console service'
Assert-True ($platformUnit -match 'ExecStartPre=/usr/local/libexec/jt-wait-console-ready') 'systemd gates platform startup on console readiness'
Assert-True ($consoleUnit -notmatch 'After=.*jt-platform') 'console has no reverse dependency on platform'
Assert-True ($ready -match 'curl --fail' -and $ready -match '"status":"UP"') 'systemd readiness checks HTTP status and expected content'
Assert-True ($release -notmatch '(?m)^\s*sleep\s+[0-9]+\s*$') 'release flow has no fixed startup wait'
Assert-True ($release.IndexOf('verify_sha256 "$JT_PLATFORM_JAR"') -lt $release.IndexOf('mktemp -d /opt/jt-platform')) 'platform digest is checked before staging'
Assert-True ($release.LastIndexOf('wait_for_http_content http://127.0.0.1:8300') -lt $release.LastIndexOf('systemctl restart jt-platform')) 'console is ready before platform starts'
Assert-True ($release -match 'wait_for_http_content http://127\.0\.0\.1:7810/health\s+''"status":"UP"''') 'release gates activation on media HTTP health content'
Assert-True ($release -match 'atomic_symlink.*current' -and $release -match 'previous') 'release uses atomic current links and retains previous versions'
Assert-True ($release -match 'rollback_release') 'failed release restores prior links'
Assert-True ($tls -match 'TLS_MODE.*production.*development') 'TLS mode is explicit'
Assert-True ($tls -match 'production TLS certificate is not readable') 'production mode requires an existing certificate'
Assert-True ($tls -match 'openssl verify -CAfile') 'production certificate trust is verified'
Assert-True ($tls -match 'checkhost|checkip') 'certificate identity is verified against PUBLIC_HOST'
Assert-True ($tls -match 'openssl req -x509' -and $tls -match 'development TLS uses a self-signed') 'self-signed generation is isolated to development mode'
Assert-True ($tls -match 'rollback_required' -and $tls -match 'backup_configuration') 'failed Nginx activation restores the prior configuration'
Assert-True ($tls -match 'curl_arguments\+=\(--insecure\)' -and $tls -match 'TLS_MODE.*production') 'insecure curl is limited to the development branch'
Assert-True ($verification -match 'file_has_identity' -and $verification -match 'service_account_is_restricted') 'deployment verification checks files and service accounts'
Assert-True ($verification -match 'jt-platform requires jt-console' -and $verification -match 'database health is UP') 'deployment verification checks ordering and health'
Assert-True ($verification -match 'media management health is UP' -and $verification -match 'http://127\.0\.0\.1:7810/health') 'deployment verification checks media HTTP health content'
Assert-True ($verification -match 'expected_location="https://\$\{PUBLIC_HOST\}\$\{path\}"' -and $verification -match '--resolve "\$\{PUBLIC_HOST\}:80:127\.0\.0\.1"' -and $verification -match '"\$status" == 301' -and $verification -match '"\$location" == "\$expected_location"') 'deployment verification checks exact same-host HTTP to HTTPS redirect'
Assert-True ($verification -match 'Nginx rejects.*private_path' -and $verification -match 'firewall_is_restricted') 'deployment verification checks path and port isolation'
Assert-True ($verification -match 'JT_CONSOLE_ADMIN_PASSWORD_FILE' -and $verification -match '--header "@\$\{api_headers\}"') 'verification reads root-only credentials and sends tokens through a header file'
Assert-True ($verification -notmatch 'echo .*administrator_password|echo .*access_token|printf.*\$administrator_password') 'verification never prints administrator passwords or access tokens'
Assert-True ($verification -notmatch 'set -x') 'verification cannot enable shell credential tracing'
Assert-True ($verification -notmatch 'tls_curl_arguments=\(--fail') 'expected 401 and 404 responses remain inspectable'

$bashCandidates = @(
    (Get-Command bash -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    'D:\work\Git\bin\bash.exe',
    'C:\Program Files\Git\bin\bash.exe'
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique

if ($bashCandidates.Count -gt 0) {
    $bash = @($bashCandidates)[0]
    Get-ChildItem $DeployRoot -Recurse -Filter '*.sh' | ForEach-Object {
        & $bash -n $_.FullName
        Assert-True ($LASTEXITCODE -eq 0) "bash syntax: $($_.Name)"
    }
    Get-ChildItem (Join-Path $DeployRoot 'tests') -Filter '*-test.sh' | ForEach-Object {
        & $bash $_.FullName
        Assert-True ($LASTEXITCODE -eq 0) "bash helper test: $($_.Name)"
    }
} else {
    Write-Host 'SKIP: bash syntax checks (bash was not found)'
}

if ($script:Failures -gt 0) {
    throw "$script:Failures deployment static test(s) failed"
}
Write-Host 'All deployment static tests passed.'
