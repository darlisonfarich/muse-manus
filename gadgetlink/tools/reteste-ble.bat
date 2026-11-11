@echo off
rem ============================================================================
rem  reteste-ble.bat - reteste BLE do gadget-link (Muse Manus), sem digitar nada.
rem  Clique duas vezes. Baixa a versao ATUAL do pc_central.py e do
rem  link_pairing_v5.json do repositorio, prepara um venv proprio (.venv-reteste),
rem  roda o autoteste e o --replay, salva o log e mostra o veredito.
rem  A logica fica em PowerShell, no fim deste arquivo (depois do "exit /b").
rem  Uso de desenvolvimento: RETESTE_AUTO=1 pula as pausas.
rem ============================================================================
setlocal EnableExtensions
title Reteste BLE - gadget-link (Muse Manus)
set "RT_SELF=%~f0"
set "RT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:RT_SELF); $i=$t.LastIndexOf(([string][char]10)+'#PS-START'); Invoke-Expression $t.Substring($i)"
set "RT_RC=%ERRORLEVEL%"
echo.
if not "%RETESTE_AUTO%"=="1" pause
exit /b %RT_RC%

#PS-START
$ErrorActionPreference = 'Continue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$Repo   = 'darlisonfarich/muse-manus'
$Branch = 'spike/gadget-link-phone'
$Build  = 'ble-transport-ca0bef2'
$Files  = @(
    @{ Path = 'gadgetlink/tools/pc_central.py';                     Name = 'pc_central.py' },
    @{ Path = 'gadgetlink/src/test/resources/link_pairing_v5.json'; Name = 'link_pairing_v5.json' }
)
$Dir     = if ($env:RT_DIR) { $env:RT_DIR.TrimEnd('\') } else { (Get-Location).Path }
$Work    = Join-Path $Dir 'reteste-ble-arquivos'
$VenvDir = Join-Path $Dir '.venv-reteste'
$Stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$Log     = Join-Path $Dir ('reteste-ble-' + $Stamp + '.log')
$Auto    = ($env:RETESTE_AUTO -eq '1')
$Utf8Bom = New-Object System.Text.UTF8Encoding($true)
$script:LogOk = $true

function Write-LogLine([string]$text) {
    if (-not $script:LogOk) { return }
    try { [IO.File]::AppendAllText($Log, $text + "`r`n", $Utf8Bom) } catch { $script:LogOk = $false }
}

function Say([string]$text, [string]$color = 'Gray') {
    Write-Host $text -ForegroundColor $color
    Write-LogLine $text
}

function Stop-Reteste([string]$title, [string[]]$lines, [int]$code) {
    Say ''
    Say ('=' * 68) 'Red'
    Say ('  ' + $title) 'Red'
    Say ('=' * 68) 'Red'
    foreach ($l in $lines) { Say ('  ' + $l) 'Red' }
    Say ''
    if ($script:LogOk) { Say ('  Log salvo em: ' + $Log) 'Yellow' }
    exit $code
}

function Wait-Key([string]$prompt) {
    Say $prompt 'Yellow'
    if ($Auto) { return }
    try { [void][Console]::ReadKey($true) } catch { [void](Read-Host) }
}

# Roda um programa pelo cmd (stderr junto com stdout), mostra cada linha na tela e no log.
function Invoke-Logged([string]$Exe, [string]$ArgLine, [switch]$Quiet) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = '/d /s /c ""' + $Exe + '" ' + $ArgLine + ' 2>&1"'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.EnvironmentVariables['PYTHONUNBUFFERED'] = '1'
    $psi.EnvironmentVariables['PYTHONIOENCODING'] = 'utf-8'
    $psi.EnvironmentVariables['PYTHONUTF8'] = '1'
    $psi.EnvironmentVariables['PIP_DISABLE_PIP_VERSION_CHECK'] = '1'
    $lines = New-Object System.Collections.ArrayList
    try { $p = [System.Diagnostics.Process]::Start($psi) } catch { return @{ Code = 9009; Lines = $lines } }
    while ($null -ne ($line = $p.StandardOutput.ReadLine())) {
        [void]$lines.Add($line)
        if (-not $Quiet) { Write-Host $line; Write-LogLine $line }
    }
    $p.WaitForExit()
    return @{ Code = $p.ExitCode; Lines = $lines }
}

# Python 3.9+ de verdade (ignora o atalho da Microsoft Store, que nao e Python).
function Find-Python {
    $cands = @(@('py', '-3'), @('python'), @('python3'))
    foreach ($c in $cands) {
        if (-not (Get-Command $c[0] -ErrorAction SilentlyContinue)) { continue }
        $rest = @()
        if ($c.Count -gt 1) { $rest = $c[1..($c.Count - 1)] }
        try {
            $out = & $c[0] @rest -c "import sys; print(sys.version_info[0]*100+sys.version_info[1]); print(sys.executable)" 2>$null
        } catch { continue }
        if ($LASTEXITCODE -ne 0 -or -not $out) { continue }
        $parts = @($out)
        if ($parts.Count -lt 2) { continue }
        $ver = 0
        if (-not [int]::TryParse(([string]$parts[0]).Trim(), [ref]$ver)) { continue }
        if ($ver -lt 309) { continue }
        $exe = ([string]$parts[1]).Trim()
        if (Test-Path -LiteralPath $exe) { return $exe }
    }
    return $null
}

# Commit atual da branch (endereco exato: nunca vem do cache do CDN). $null se a API nao respondeu.
function Get-RepoHead {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri ('https://api.github.com/repos/' + $Repo + '/commits/' + $Branch) -Headers @{ 'Accept' = 'application/vnd.github.sha'; 'User-Agent' = 'reteste-ble' } -TimeoutSec 30
        $c = $r.Content
        if ($c -is [byte[]]) { $c = [Text.Encoding]::ASCII.GetString($c) }
        $s = ([string]$c).Trim()
        if ($s -match '^[0-9a-f]{40}$') { return $s }
    } catch {}
    return $null
}

function Test-Downloaded([string]$name, [string]$file) {
    if (-not (Test-Path -LiteralPath $file)) { return $false }
    if ((Get-Item -LiteralPath $file).Length -lt 100) { return $false }
    $text = [IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
    if ($name -eq 'pc_central.py') { return ($text.Contains('def selftest') -and $text.Contains('REPLAY_DROP_DEADLINE_S')) }
    try { $j = $text | ConvertFrom-Json; return ($null -ne $j.vectors) } catch { return $false }
}

# Baixa um arquivo do repositorio; sem o commit, usa a branch com um parametro unico que fura o cache.
function Get-RepoFile([string]$sha, [string]$path, [string]$dest) {
    if ($sha) { $url = 'https://raw.githubusercontent.com/' + $Repo + '/' + $sha + '/' + $path }
    else { $url = 'https://raw.githubusercontent.com/' + $Repo + '/' + $Branch + '/' + $path + '?cb=' + [DateTime]::UtcNow.Ticks }
    $tmp = $dest + '.download'
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp -TimeoutSec 90 -Headers @{ 'User-Agent' = 'reteste-ble' }
    Move-Item -LiteralPath $tmp -Destination $dest -Force
}

# Veredito a partir do codigo de saida e da saida do pc_central.py --replay.
function Get-Verdict([int]$code, $lines) {
    $all = @($lines)
    $seconds = $null
    foreach ($l in $all) { if ($l -match '^ok\s+queda em ([0-9]+[.,][0-9]+) s') { $seconds = $Matches[1] } }
    $fail = $null
    foreach ($l in $all) { if ($l -match '^FALHA') { $fail = $l; break } }
    if ($code -eq 0 -and $null -eq $fail) {
        return @{ Passed = $true; Seconds = $seconds; Reason = ''; Hint = '' }
    }
    $reason = $fail
    if (-not $reason) {
        $reason = ($all | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 1)
        if (-not $reason) { $reason = '(sem saida; codigo de saida ' + $code + ')' }
    }
    $hint = 'Veja o log para o passo exato em que parou.'
    if ($reason -match '^FALHA\s+queda em') { $hint = 'O celular derrubou a conexao, mas DEPOIS de 1,5 s. O problema e no celular (a queda forcada esta lenta).' }
    elseif ($reason -match 'derrubou a conex') { $hint = 'O celular NAO derrubou a conexao depois do registro repetido. O problema e no celular.' }
    elseif ($reason -match 'gadget encontrado') { $hint = 'Nao achei o gadget: Bluetooth ligado no notebook e no celular? Pareamento aberto no celular (aguardando o app Muse)? Celular perto?' }
    elseif ($reason -match 'No Bluetooth adapter|BleakBluetoothNotAvailable') { $hint = 'Este notebook nao tem Bluetooth disponivel (adaptador ausente ou desligado). Ligue o Bluetooth do Windows (Configuracoes > Bluetooth e dispositivos) e rode de novo.' } elseif ($reason -match 'Traceback|Error|Exception') { $hint = 'Erro do programa (veja o log). Se foi no Bluetooth do Windows, desligue e ligue o Bluetooth do notebook e rode de novo.' }
    return @{ Passed = $false; Seconds = $seconds; Reason = $reason; Hint = $hint }
}

function Main {
    try { Clear-Host } catch {}
    Say ('=' * 68) 'Cyan'
    Say '  RETESTE BLE - gadget-link (Muse Manus)' 'Cyan'
    Say ('=' * 68) 'Cyan'
    Say ('  Data: ' + (Get-Date -Format 'dd/MM/yyyy HH:mm:ss') + '   PC: ' + $env:COMPUTERNAME)
    Say ('  Pasta: ' + $Dir)
    Say ''

    # --- 1) Python ---------------------------------------------------------------
    Say '[1/6] Procurando o Python...' 'White'
    $py = Find-Python
    if (-not $py) {
        Stop-Reteste 'PYTHON NAO ENCONTRADO' @(
            'Este reteste precisa do Python 3.9 ou mais novo, e ele nao esta instalado.',
            '',
            'Instale em UM destes jeitos e depois clique neste arquivo de novo:',
            '  - https://www.python.org/downloads/windows/  (baixe o instalador e MARQUE',
            '    a caixa "Add python.exe to PATH" na primeira tela)',
            '  - ou, no Prompt de Comando:  winget install Python.Python.3.12',
            '',
            'Obs.: o atalho "python" da Microsoft Store que abre a loja NAO conta.'
        ) 3
    }
    Say ('      Python: ' + $py) 'Green'

    # --- 2) Arquivos atuais do repositorio ---------------------------------------
    Say ''
    Say '[2/6] Baixando a versao ATUAL do pc_central.py e do link_pairing_v5.json...' 'White'
    try { New-Item -ItemType Directory -Force -Path $Work | Out-Null } catch {}
    foreach ($f in $Files) { Remove-Item -LiteralPath (Join-Path $Work $f.Name) -Force -ErrorAction SilentlyContinue }
    $sha = Get-RepoHead
    if ($sha) { Say ('      Commit da branch ' + $Branch + ': ' + $sha) 'Green' }
    else { Say '      (nao consegui o numero do commit; baixando da branch direto)' 'Yellow' }
    foreach ($f in $Files) {
        $dest = Join-Path $Work $f.Name
        try { Get-RepoFile $sha $f.Path $dest } catch {
            Stop-Reteste 'NAO CONSEGUI BAIXAR OS ARQUIVOS DO REPOSITORIO' @(
                ('Arquivo: ' + $f.Path),
                'Sem internet, ou o GitHub esta fora do ar, ou o repositorio ficou privado.',
                'Por seguranca o reteste NAO usa copia velha da pasta. Tente de novo.'
            ) 4
        }
        if (-not (Test-Downloaded $f.Name $dest)) {
            Stop-Reteste 'ARQUIVO BAIXADO INVALIDO' @(
                ('Arquivo: ' + $f.Path),
                'O conteudo baixado nao parece o arquivo certo. Tente de novo mais tarde.'
            ) 4
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash.ToLower()
        Say ('      ok  ' + $f.Name + '  sha256 ' + $hash.Substring(0, 16) + '...') 'Green'
    }
    $PcCentral = Join-Path $Work 'pc_central.py'

    # --- 3) venv proprio ---------------------------------------------------------
    Say ''
    Say '[3/6] Preparando o ambiente Python (.venv-reteste)...' 'White'
    $VenvPy = Join-Path $VenvDir 'Scripts\python.exe'
    if (Test-Path -LiteralPath $VenvPy) {
        $t = Invoke-Logged $VenvPy '-c "import sys"' -Quiet
        if ($t.Code -ne 0) {
            Say '      o venv antigo esta quebrado; recriando...' 'Yellow'
            Remove-Item -LiteralPath $VenvDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if (-not (Test-Path -LiteralPath $VenvPy)) {
        Say '      criando o venv (uma vez so)...'
        $r = Invoke-Logged $py ('-m venv "' + $VenvDir + '"')
        if ($r.Code -ne 0 -or -not (Test-Path -LiteralPath $VenvPy)) {
            Stop-Reteste 'NAO CONSEGUI CRIAR O VENV' @(
                'O comando "python -m venv" falhou (veja as linhas acima e o log).',
                'Apague a pasta .venv-reteste, se existir, e tente de novo.'
            ) 5
        }
    }
    $chk = Invoke-Logged $VenvPy '-c "import bleak, cryptography"' -Quiet
    if ($chk.Code -ne 0) {
        Say '      instalando bleak e cryptography (pode levar um minuto)...'
        $r = Invoke-Logged $VenvPy '-m pip install bleak cryptography'
        $chk = Invoke-Logged $VenvPy '-c "import bleak, cryptography"' -Quiet
        if ($r.Code -ne 0 -or $chk.Code -ne 0) {
            Stop-Reteste 'NAO CONSEGUI INSTALAR bleak E cryptography' @(
                'O pip falhou (veja as linhas acima e o log). Sem internet?',
                'Rode de novo com a internet funcionando.'
            ) 5
        }
    }
    $ver = Invoke-Logged $VenvPy '-c "from importlib.metadata import version as v; print(''bleak'', v(''bleak''), ''cryptography'', v(''cryptography''))"' -Quiet
    if ($ver.Code -eq 0 -and $ver.Lines.Count -gt 0) { Say ('      ' + $ver.Lines[0]) 'Green' } else { Say '      bleak e cryptography ok' 'Green' }

    # --- 4) O que o celular precisa ter pronto -----------------------------------
    Say ''
    Say '[4/6] ANTES DE RODAR, confira no celular (POCO):' 'White'
    Say ''
    Say ('   1. Build ' + $Build + ' INSTALADO (Sobre mostra o commit).') 'Yellow'
    Say ('      https://github.com/' + $Repo + '/releases/tag/' + $Build) 'Gray'
    Say '   2. Bluetooth LIGADO no celular E neste notebook.' 'Yellow'
    Say '   3. Pareamento ABERTO no app: Diagnostics > Gadget link (spike) >' 'Yellow'
    Say '      Abrir pareamento. Tem que aparecer "waiting for the Muse app".' 'Yellow'
    Say '      (a janela de pareamento dura 10 minutos)' 'Gray'
    Say '   4. Celular perto do notebook, com a tela ligada.' 'Yellow'
    Say ''
    Wait-Key '   Quando estiver tudo pronto, aperte uma tecla para comecar...'

    # --- 5) Autoteste -------------------------------------------------------------
    Say ''
    Say '[5/6] Autoteste (criptografia contra os vetores oficiais, sem Bluetooth)...' 'White'
    $sf = Invoke-Logged $VenvPy ('-u "' + $PcCentral + '" --selftest')
    if ($sf.Code -ne 0) {
        Stop-Reteste 'AUTOTESTE FALHOU - o problema e NO PC, nao no celular' @(
            'O programa de teste no notebook nao passou na propria conferencia, antes de',
            'falar com o celular. Nao adianta mexer no celular agora.',
            'Apague a pasta .venv-reteste e a pasta reteste-ble-arquivos e rode de novo.',
            'Se repetir, mande o log para analise.'
        ) 2
    }
    Say '      autoteste ok' 'Green'

    # --- 6) Reteste de verdade -----------------------------------------------------
    Say ''
    Say '[6/6] Reteste BLE (--replay): procura o celular, pareia, repete um registro' 'White'
    Say '      e mede em quanto tempo o celular derruba a conexao...' 'White'
    Say ''
    $rp = Invoke-Logged $VenvPy ('-u "' + $PcCentral + '" --replay')
    $v = Get-Verdict $rp.Code $rp.Lines

    Say ''
    Say ('=' * 68) 'White'
    if ($v.Passed) {
        $t = ''
        if ($v.Seconds) { $t = ' (queda em ' + $v.Seconds + ' s)' }
        Say ('  VEREDITO: PASSOU' + $t) 'Green'
        Say '  O celular derrubou a conexao em ate 1,5 s depois do registro repetido.' 'Green'
    } else {
        Say '  VEREDITO: FALHOU' 'Red'
        Say ('  Motivo: ' + $v.Reason) 'Red'
        Say ('  ' + $v.Hint) 'Red'
    }
    Say ('=' * 68) 'White'
    Say ''
    Say '  LEMBRETE - segundo teste, MANUAL, no celular:' 'Yellow'
    Say '    Abra o pareamento (Diagnostics > Gadget link > Abrir pareamento) e FECHE' 'Yellow'
    Say '    no meio da abertura, logo depois de tocar. Tem que fechar NA HORA e NAO' 'Yellow'
    Say '    aparecer como erro (a fase deve voltar para "off", nunca para "error").' 'Yellow'
    Say ''
    if ($script:LogOk) { Say ('  Log salvo em: ' + $Log) 'Cyan' }
    if ($v.Passed) { exit 0 } else { exit 1 }
}

if ($env:RETESTE_LIB -ne '1') { Main }
