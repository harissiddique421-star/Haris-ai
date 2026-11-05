# Source exports omit artwork with unverified redistribution rights.
# Create a neutral letter mark only when artwork is absent; never overwrite it.
param()
$ErrorActionPreference = 'Stop'
$buildRoot = Join-Path (Split-Path $PSScriptRoot) 'build'
$png = Join-Path $buildRoot 'icon.png'
$ico = Join-Path $buildRoot 'icon.ico'
if ((Test-Path -LiteralPath $png) -and (Test-Path -LiteralPath $ico)) { return }
New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $png)) {
  Add-Type -AssemblyName System.Drawing
  $bitmap = [Drawing.Bitmap]::new(256, 256)
  $graphics = [Drawing.Graphics]::FromImage($bitmap)
  $font = [Drawing.Font]::new('Segoe UI', 174, [Drawing.FontStyle]::Bold, [Drawing.GraphicsUnit]::Pixel)
  $brush = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(142, 225, 219))
  $format = [Drawing.StringFormat]::new()
  try {
    $graphics.Clear([Drawing.Color]::FromArgb(15, 22, 27))
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $format.Alignment = [Drawing.StringAlignment]::Center
    $format.LineAlignment = [Drawing.StringAlignment]::Center
    $graphics.DrawString('M', $font, $brush, [Drawing.RectangleF]::new(0, 0, 256, 256), $format)
    $bitmap.Save($png, [Drawing.Imaging.ImageFormat]::Png)
  } finally {
    $format.Dispose(); $brush.Dispose(); $font.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
  }
}
if (-not (Test-Path -LiteralPath $ico)) { & (Join-Path $PSScriptRoot 'create-app-ico.ps1') -SourcePng $png -OutputIco $ico }
