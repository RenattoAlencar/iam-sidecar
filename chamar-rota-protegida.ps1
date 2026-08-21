# ============================================================================
#  Chama uma rota da matriz no sidecar que esta rodando local.
#
#  Confirma a cadeia inteira: a matriz decide, a jornada e disparada no gateway
#  real, e o desafio volta ao canal.
#
#  Rodar em OUTRO terminal, com o sidecar ja de pe.
#      .\scripts\chamar-rota-protegida.ps1
# ============================================================================

# --- PREENCHER ---------------------------------------------------------------

# JWT do canal. Expira em cerca de uma hora — pegar um fresco antes de rodar.
$TOKEN = ""

# Nome do cabecalho. O MESMO configurado no script que subiu o sidecar.
$CABECALHO_TOKEN = ""

# Rota que esta na matriz do application.yml.
$ROTA   = "/api/v1/pix/transferencia"
$METODO = "POST"

# -----------------------------------------------------------------------------

if (-not $TOKEN -or -not $CABECALHO_TOKEN) {
    Write-Host "Preencher TOKEN e CABECALHO_TOKEN no inicio deste arquivo." -ForegroundColor Red
    exit 1
}

$URL = "127.0.0.1:8080$ROTA"

Write-Host ""
Write-Host ("=" * 70)
Write-Host "ROTA DA MATRIZ — a jornada deve ser disparada no gateway real"
Write-Host ("=" * 70)
Write-Host "$METODO $URL"
Write-Host "Token: $($TOKEN.Substring(0, [Math]::Min(20, $TOKEN.Length)))... ($($TOKEN.Length) caracteres)"
Write-Host ""

curl.exe -i -X $METODO $URL -H "${CABECALHO_TOKEN}: $TOKEN" -H "Content-Type: application/json" -d '{}'

Write-Host ""
Write-Host ("=" * 70)
Write-Host "COMO LER O RESULTADO"
Write-Host ("=" * 70)
Write-Host "401 com challenge_required  -> a cadeia funciona; o desafio veio do gateway real"
Write-Host "401 com session_required    -> o cabecalho do token nao chegou; conferir o nome"
Write-Host "403 com denied              -> o gateway recusou; token expirado ou claim ausente"
Write-Host "503                         -> o gateway nao respondeu; conferir rede e endereco"
Write-Host "200 com resposta do BFF     -> a rota NAO esta na matriz; conferir o log de boot"
Write-Host ""
Write-Host "No console do sidecar, procurar:"
Write-Host "  Rota interceptada: regra=..."
Write-Host "  Iniciando a jornada '...' no realm '...'"
Write-Host "  Passo de inicio recebeu desafio: ..."
Write-Host ""
