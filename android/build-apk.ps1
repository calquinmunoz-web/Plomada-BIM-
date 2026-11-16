# Compila Plomada BIM como APK sin Gradle: solo necesita un JDK 17 y el SDK de Android.
#   pwsh android\build-apk.ps1 -Jdk "C:\ruta\al\jdk-17"
param(
  [Parameter(Mandatory)] [string]$Jdk,
  [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
  [string]$BuildTools = "35.0.0",
  [string]$Platform = "android-34"
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent
$bt = "$Sdk\build-tools\$BuildTools"
$jar = "$Sdk\platforms\$Platform\android.jar"
$out = "$here\build"
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"
function Run($exe) { & $exe @args; if ($LASTEXITCODE -ne 0) { throw "Falló: $exe $args" } }

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\assets\www", "$out\res", "$out\gen", "$out\classes", "$out\dex" | Out-Null

# 1. Página: la misma que la versión web, con las librerías empaquetadas para trabajar sin conexión
$html = [IO.File]::ReadAllText("$root\plomada-bim.html", [Text.Encoding]::UTF8)
$html = $html -replace '<link rel="stylesheet" href="https://fonts\.googleapis\.com[^>]*>\r?\n', ''
$html = $html.Replace('https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js', 'three.min.js')
$html = $html.Replace('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/', '')
$html = $html.Replace('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/', '')
$html = $html.Replace('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/libs/', '')
$html = $html.Replace('https://cdn.jsdelivr.net/npm/web-ifc@0.0.57/', '')
if ($html -match 'https://(cdn|fonts)') { throw 'Quedan referencias externas en la página' }
$page = '<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no">' +
        '<style>body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style></head><body>' + $html + '</body></html>'
[IO.File]::WriteAllText("$out\assets\www\index.html", $page, (New-Object Text.UTF8Encoding $false))
Copy-Item "$here\web-libs\*" "$out\assets\www\"
Copy-Item "$here\www-extra\*" "$out\assets\www\"   # visor de Autodesk para archivos NWD
Copy-Item "$root\web-ifc.wasm" "$out\assets\www\"

# 2. Recursos y manifiesto
Run "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
Run "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$here\AndroidManifest.xml" --java "$out\gen" "$out\res.zip"

# 3. Código
$src = @(Get-ChildItem "$here\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run "$Jdk\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 '-Xlint:-options' -classpath $jar -d "$out\classes" @src
$cls = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Run "$bt\d8.bat" --release --min-api 24 --lib $jar --output "$out\dex" @cls
Push-Location "$out\dex"; try { Run "$bt\aapt.exe" add "$out\unsigned.apk" classes.dex | Out-Null } finally { Pop-Location }
# Los assets se añaden con rutas «/»: aapt2 -A en Windows los guarda con «\» y Android no los encuentra
$assets = @(Get-ChildItem "$out\assets\www" -File | ForEach-Object { "assets/www/$($_.Name)" })
Push-Location $out; try { Run "$bt\aapt.exe" add "$out\unsigned.apk" @assets | Out-Null } finally { Pop-Location }

# 4. Alinear y firmar (la clave se crea la primera vez; consérvala para poder publicar actualizaciones)
Run "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
$ks = "$here\plomada.keystore"
if (-not (Test-Path $ks)) {
  Run "$Jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass plomada-bim -keypass plomada-bim -alias plomada -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Plomada BIM"
}
$apk = "$root\PlomadaBIM.apk"
Run "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:plomada-bim --ks-key-alias plomada --out $apk "$out\aligned.apk"
Remove-Item "$apk.idsig" -ErrorAction SilentlyContinue
Run "$bt\apksigner.bat" verify --verbose $apk
"APK: $apk ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)"
