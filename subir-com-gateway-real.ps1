# ============================================================================
#  Sobe o sidecar local apontando para o gateway de identidade real.
#
#  Serve para confirmar a cadeia inteira: matriz -> jornada -> gateway -> canal.
#  Os cenarios do Docker usam gateway simulado; este usa o de verdade.
#
#  Executar da RAIZ DO PROJETO:
#      .\scripts\subir-com-gateway-real.ps1
# ============================================================================

# --- PREENCHER ---------------------------------------------------------------

# Endereco do gateway, ate o caminho base. SEM barra no final.
$GATEWAY = ""

# Jornada do canal que se quer exercitar.
$JORNADA = "pdc-bank-authz-consultivo"

# Nome do cabecalho pelo qual o canal apresenta o token.
$CABECALHO_TOKEN = ""

# -----------------------------------------------------------------------------

if (-not $GATEWAY -or -not $CABECALHO_TOKEN) {
    Write-Host "Preencher GATEWAY e CABECALHO_TOKEN no inicio deste arquivo." -ForegroundColor Red
    exit 1
}

# Configuracao do gateway
$env:IDENTITY_BASE_URL             = $GATEWAY
$env:IDENTITY_REALM                = "alpha"
$env:IDENTITY_JOURNEY              = $JORNADA
$env:IDENTITY_JOURNEY_TYPE         = "service"
$env:IDENTITY_CHANNEL_TOKEN_HEADER = $CABECALHO_TOKEN

# Obrigatorios para o boot, mas nao usados no primeiro passo da jornada.
# Preencher com os valores reais quando for exercitar a emissao do token.
$env:IDENTITY_CLIENT_ID            = "nao-usado-no-primeiro-passo"
$env:IDENTITY_CLIENT_SECRET        = "nao-usado-no-primeiro-passo"
$env:IDENTITY_REDIRECT_URI         = "https://nao-usado-no-primeiro-passo/callback"
$env:IDENTITY_SESSION_COOKIE_NAME  = "nao-usado-no-primeiro-passo"

# O BFF nao e alcancado nas rotas da matriz — elas sao barradas antes.
# Se o eco do Docker estiver de pe, ele atende as rotas comuns.
$env:SIDECAR_TARGET                = "http://127.0.0.1:8081"

# DEBUG para acompanhar a jornada no console.
$env:LOG_LEVEL_SIDECAR             = "DEBUG"

Write-Host ""
Write-Host "Gateway:   $GATEWAY"        -ForegroundColor Cyan
Write-Host "Jornada:   $JORNADA"        -ForegroundColor Cyan
Write-Host "Cabecalho: $CABECALHO_TOKEN" -ForegroundColor Cyan
Write-Host ""
Write-Host "Subindo o sidecar em 127.0.0.1:8080 ..." -ForegroundColor Yellow
Write-Host "Depois que subir, abrir OUTRO terminal e rodar chamar-rota-protegida.ps1"
Write-Host ""

mvn spring-boot:run
