[CmdletBinding()]
param(
    [switch]$Quick,
    [ValidateRange(1, 1000000)]
    [int]$Warmups = 200,
    [ValidateRange(1, 1000000)]
    [int]$Iterations = 1200,
    [ValidateRange(0, 64)]
    [int]$LoadThreads = 1,
    [ValidateRange(1.0, 100.0)]
    [double]$SafetyFactor = 4.0,
    [string]$BuildDirectory,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

if ($Quick -and -not $PSBoundParameters.ContainsKey('Warmups')) {
    $Warmups = 50
}
if ($Quick -and -not $PSBoundParameters.ContainsKey('Iterations')) {
    $Iterations = 300
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $BuildDirectory) {
    $BuildDirectory = Join-Path $repoRoot 'build\audio-perf'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $BuildDirectory 'results'
}

$configure = @(
    '-S', $PSScriptRoot,
    '-B', $BuildDirectory,
    '-DCMAKE_BUILD_TYPE=Release'
)
if (Get-Command ninja -ErrorAction SilentlyContinue) {
    $configure += @('-G', 'Ninja')
}
if (-not $env:CXX -and (Get-Command g++ -ErrorAction SilentlyContinue)) {
    $configure += '-DCMAKE_CXX_COMPILER=g++'
}

& cmake @configure
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
& cmake --build $BuildDirectory --config Release --parallel
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$executable = Join-Path $BuildDirectory 'Release\audio_pipeline_benchmark.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    $executable = Join-Path $BuildDirectory 'audio_pipeline_benchmark.exe'
}
if (-not (Test-Path -LiteralPath $executable)) {
    throw "Benchmark executable was not produced under $BuildDirectory"
}

$benchmark = @(
    '--warmups', $Warmups,
    '--iterations', $Iterations,
    '--load-threads', $LoadThreads,
    '--safety-factor', $SafetyFactor.ToString([Globalization.CultureInfo]::InvariantCulture),
    '--output-dir', $OutputDirectory
)
if ($Quick) {
    # Put --quick first so explicitly requested sample counts override its defaults.
    $benchmark = @('--quick') + $benchmark
}

& $executable @benchmark
$benchmarkExit = $LASTEXITCODE

$reportPath = Join-Path $OutputDirectory 'audio_pipeline_results.json'
$schemaPath = Join-Path $PSScriptRoot 'audio_pipeline_result.schema.json'
$validatorPath = Join-Path $PSScriptRoot 'validate_audio_pipeline_report.py'
$python = Get-Command python3 -ErrorAction SilentlyContinue
if (-not $python) {
    $python = Get-Command python -ErrorAction SilentlyContinue
}
if (-not $python) {
    throw 'Python 3 is required to validate the emitted benchmark report'
}
if (Test-Path -LiteralPath $reportPath) {
    & $python.Source $validatorPath $schemaPath $reportPath
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} elseif ($benchmarkExit -eq 0) {
    throw "Benchmark exited successfully without emitting $reportPath"
}
exit $benchmarkExit
