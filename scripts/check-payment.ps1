$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $taskRoot '.work\payment-checks'
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.zxing\core\3.5.3'
$jar = Get-ChildItem -Path $cache -Filter 'core-3.5.3.jar' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw '缺少 ZXing 依赖，请先运行 .\gradlew.bat :app:assembleDebug。' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$source = Join-Path $taskRoot 'app\src\main\java\dev\akai\toolbox\payment\CodeLocator.java'
$checks = Join-Path $taskRoot 'tests\PaymentCodeChecks.java'
$gate = Join-Path $taskRoot 'app\src\main\java\dev\akai\toolbox\payment\PaymentEventGate.java'
$gateChecks = Join-Path $taskRoot 'tests\PaymentEventGateChecks.java'
& javac -encoding UTF-8 -cp $jar.FullName -d $output $source $checks $gate $gateChecks
if ($LASTEXITCODE -ne 0) { throw '付款码图形检查编译失败。' }
$classPath = $output + [IO.Path]::PathSeparator + $jar.FullName
& java -cp $classPath PaymentCodeChecks
if ($LASTEXITCODE -ne 0) { throw '付款码图形检查失败。' }
& java -cp $classPath PaymentEventGateChecks
if ($LASTEXITCODE -ne 0) { throw '付款页事件顺序检查失败。' }
