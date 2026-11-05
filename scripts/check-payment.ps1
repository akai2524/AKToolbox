$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $taskRoot '.work\payment-checks'
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.zxing\core\3.5.3'
$jar = Get-ChildItem -Path $cache -Filter 'core-3.5.3.jar' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw 'missing zxing jar' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$source = Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\payment\CodeLocator.java'
$layout = Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\payment\PaymentLayout.java'
$palette = Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\payment\PaymentPalette.java'
$checks = Join-Path $taskRoot 'tests\PaymentCodeChecks.java'
$gate = Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\payment\PaymentEventGate.java'
$gateChecks = Join-Path $taskRoot 'tests\PaymentEventGateChecks.java'
& javac -encoding UTF-8 -cp $jar.FullName -d $output $source $layout $palette $checks $gate $gateChecks
if ($LASTEXITCODE -ne 0) { throw 'payment check compile failed' }
$classPath = $output + [IO.Path]::PathSeparator + $jar.FullName
& java -cp $classPath PaymentCodeChecks
if ($LASTEXITCODE -ne 0) { throw 'payment code checks failed' }
& java -cp $classPath PaymentEventGateChecks
if ($LASTEXITCODE -ne 0) { throw 'payment event gate checks failed' }
