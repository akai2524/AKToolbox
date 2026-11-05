$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskRoot '.work\background-policy-checks'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
& javac -encoding UTF-8 -d $taskOutput `
    (Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\core\BackgroundPolicyController.java') `
    (Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\core\RootAccessCheck.java') `
    (Join-Path $taskRoot 'tests\RootAccessCheckTests.java') `
    (Join-Path $taskRoot 'tests\BackgroundPolicyChecks.java')
if ($LASTEXITCODE -ne 0) { throw '后台策略检查编译失败。' }
& java -cp $taskOutput BackgroundPolicyChecks
if ($LASTEXITCODE -ne 0) { throw '后台策略检查失败。' }
& java -cp $taskOutput RootAccessCheckTests
if ($LASTEXITCODE -ne 0) { throw 'Root 权限检查失败。' }
