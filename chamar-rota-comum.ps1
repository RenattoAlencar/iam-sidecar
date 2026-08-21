# ============================================================================
#  Chama uma rota FORA da matriz, para confirmar que o sidecar e transparente.
#
#  Precisa de algo escutando em 127.0.0.1:8081 fazendo o papel do BFF. O eco do
#  Docker serve:
#      docker run -d --name bff-local -p 8081:8081 -e HTTP_PORT=8081 mendhak/http-https-echo:37
#
#      .\scripts\chamar-rota-comum.ps1
# ============================================================================

$ROTA = "/api/v1/conta/extrato"
$URL  = "127.0.0.1:8080$ROTA"

Write-Host ""
Write-Host ("=" * 70)
Write-Host "ROTA FORA DA MATRIZ — deve atravessar sem verificacao"
Write-Host ("=" * 70)
Write-Host "GET $URL"
Write-Host ""

curl.exe -i $URL

Write-Host ""
Write-Host ("=" * 70)
Write-Host "COMO LER O RESULTADO"
Write-Host ("=" * 70)
Write-Host "200 com resposta do BFF -> correto; nenhuma verificacao aconteceu"
Write-Host "401                     -> a rota esta na matriz; conferir o log de boot"
Write-Host "502                     -> nada escutando em 127.0.0.1:8081"
Write-Host ""
Write-Host "Reparar na resposta:"
Write-Host "  X-Correlation-Id  -> escrito pelo sidecar, o BFF nao gera"
Write-Host "  host: 127.0.0.1:8081 -> reescrito para o destino real"
Write-Host "  x-forwarded-host  -> o endereco pelo qual voce entrou"
Write-Host ""
