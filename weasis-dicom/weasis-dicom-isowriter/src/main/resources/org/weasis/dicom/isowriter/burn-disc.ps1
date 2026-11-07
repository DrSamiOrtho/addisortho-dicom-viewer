# Copyright (c) 2026 Addis Ortho Trading PLC. SPDX-License-Identifier: Apache-2.0
#
# Burns a folder to the blank CD or DVD in the first drive that has one, then ejects it, with the
# Windows burning service (IMAPI2), so nothing has to be installed.
#   -Check   only tells whether a blank disc is ready.
# Exit codes: 0 done (or blank disc ready), 3 no blank disc, 4 no burner, 1 burning failed.
param([string]$Folder, [string]$Label = "DICOM", [switch]$Check)
$ErrorActionPreference = 'Stop'

try {
  $master = New-Object -ComObject IMAPI2.MsftDiscMaster2
} catch {
  exit 4
}
if (-not $master.IsSupportedEnvironment -or $master.Count -eq 0) { exit 4 }

for ($i = 0; $i -lt $master.Count; $i++) {
  $rec = New-Object -ComObject IMAPI2.MsftDiscRecorder2
  $fmt = New-Object -ComObject IMAPI2.MsftDiscFormat2Data
  try {
    $rec.InitializeDiscRecorder($master.Item($i))
    if (-not $fmt.IsRecorderSupported($rec)) { continue }
    if (-not $fmt.IsCurrentMediaSupported($rec)) { continue }
    $fmt.Recorder = $rec
    if (-not $fmt.MediaHeuristicallyBlank) { continue }
  } catch {
    continue
  }
  if ($Check) { exit 0 }

  try {
    $fs = New-Object -ComObject IMAPI2FS.MsftFileSystemImage
    $fs.ChooseImageDefaults($rec)
    # ISO 9660 and Joliet (long names) on every disc, plus UDF where the defaults chose it (DVD).
    $fs.FileSystemsToCreate = $fs.FileSystemsToCreate -bor 3
    $fs.VolumeName = $Label
    $fs.Root.AddTree($Folder, $false)
    $image = $fs.CreateResultImage()
    $fmt.ClientName = 'Addis Ortho Viewer'
    $fmt.ForceMediaToBeClosed = $true
    $fmt.Write($image.ImageStream)
    $rec.EjectMedia()
    exit 0
  } catch {
    Write-Output $_.Exception.Message
    exit 1
  }
}
exit 3
