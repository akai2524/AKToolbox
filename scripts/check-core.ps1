$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutput = Join-Path $taskRoot '.work\core-checks'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskSources = @(
    (Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\core\PackageCatalog.java'),
    (Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\core\GappsController.java'),
    (Join-Path $taskRoot 'app\src\main\java\dev\ak\toolbox\core\LegacyGappsRecovery.java'),
    (Join-Path $taskRoot 'tests\CoreChecks.java')
)
& javac -encoding UTF-8 -d $taskOutput @taskSources
if ($LASTEXITCODE -ne 0) { throw 'core checks compile failed' }
& java -cp $taskOutput CoreChecks
if ($LASTEXITCODE -ne 0) { throw 'core checks failed' }
