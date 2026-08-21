# Cenários de teste — sidecar de confirmação

Documento de execução. Cada cenário descreve o que se está verificando, o
comando, o resultado esperado e como interpretar uma divergência.

Ambiente: dois canais em containers, com gateway de identidade simulado.
Comandos em PowerShell.

---

## Preparação

```powershell
mvn clean package -DskipTests
cd demo
docker compose down
docker compose build
docker compose up -d
docker compose ps
```

Os dois sidecars precisam aparecer como `healthy`.

### Verificação obrigatória antes de começar

```powershell
docker compose logs sidecar-pdc  | Select-String "Matriz de interceptação" -Context 0,4
docker compose logs sidecar-ciam | Select-String "Matriz de interceptação" -Context 0,3
```

| Instância | Regras | Jornada |
|-----------|--------|---------|
| PDC | 3 | `pdc-bank-authz-consultivo` |
| CIAM | 2 | `factor-onboarding` |

**Se o número não bater, pare.** A configuração não chegou, e todos os cenários
seguintes vão produzir resultado enganoso — uma rota ausente da matriz responde
`200` como se estivesse tudo certo.

---

## Como ler os resultados

O corpo da resposta diz quem respondeu:

| A resposta tem | Quem respondeu |
|----------------|----------------|
| dados de negócio, e o cabeçalho `x-powered-by` | **BFF** — a requisição atravessou |
| `{"error": ...}` ou `{"status": ...}`, corpo curto | **sidecar** — parou ali |

Nos cenários de recusa, é isso que importa. Um sidecar que responde o status
certo mas encaminha assim mesmo passaria num teste que só olhasse o código.

---

# Grupo 1 — O componente é transparente

## 1.1 Rota fora da matriz atravessa

**O que se verifica:** o tráfego que não precisa de confirmação não paga
nenhuma verificação, e o canal não percebe que existe um salto a mais.

```powershell
curl.exe -i localhost:18080/api/v1/clientes/extrato
```

**Esperado:** `200`, com a resposta do BFF.

**Se der outro código:** a rota entrou na matriz por engano, ou o BFF não subiu.
Conferir a matriz no log de boot.

---

## 1.2 Corpo e query atravessam sem alteração

**O que se verifica:** o sidecar não lê nem modifica o conteúdo. Cada canal
envia o que precisa, e nada trava.

```powershell
curl.exe -i "localhost:18080/api/v1/clientes/extrato?de=2026-01-01&ate=2026-01-31" `
  -H "Content-Type: application/json" `
  -d '{\"filtro\":\"todos\"}'
```

**Esperado:** `200`, e no eco do BFF a query com os dois parâmetros e o corpo
intacto.

**Por que isso importa:** o sidecar decide por endereço e verbo apenas. Ler o
corpo obrigaria a mantê-lo em memória, e o corpo é controlado por quem está
sendo verificado.

---

# Grupo 2 — A jornada do PDC (consultiva)

## 2.1 A consulta sensível dispara a jornada

**O que se verifica:** rota da matriz é barrada, e o canal recebe o desafio para
apresentar ao cliente.

```powershell
curl.exe -i localhost:18080/api/v1/clientes/dados-cadastrais `
  -H "x-canal-autenticacao: eyJhbGciOiJIUzI1NiJ9.jwt-do-cognito.assinatura"
```

**Esperado:** `401` com o deeplink e o intervalo do polling:

```json
{
  "status": "challenge_required",
  "authId": "eyJ0eXAiOiJKV1Qi...",
  "callbacks": [
    { "type": "PollingWaitCallback",
      "output": [
        { "name": "waitTime", "value": "8000" },
        { "name": "message", "value": "portosuperapp://porto/portobank/pdc?..." }
      ] },
    { "type": "ConfirmationCallback",
      "output": [ { "name": "options", "value": ["Cancel"] } ] }
  ]
}
```

**O que reparar:** não há resposta do BFF no corpo. A consulta **não foi
executada** — a requisição morreu no sidecar.

O canal recebeu três coisas: o deeplink para abrir no aplicativo, o intervalo
para o polling, e a opção de cancelar.

**Se der `503`:** o gateway simulado não subiu.
**Se der `403`:** o gateway recusou — conferir se o cabeçalho do token foi
enviado.

---

## 2.2 O canal faz o polling

**O que se verifica:** o canal aguarda e reenvia o mesmo identificador, com
callbacks vazios, até o aplicativo resolver.

Copie o `authId` da resposta anterior e execute **três vezes**:

```powershell
curl.exe -i -X POST localhost:18080/ciam/challenge `
  -H "Content-Type: application/json" `
  -d '{\"authId\":\"<COLE O authId AQUI>\",\"callbacks\":[]}'
```

**Esperado nas duas primeiras:** `401` com `PollingWaitCallback` — o aplicativo
ainda não resolveu.

**Esperado na terceira:** `200` com `{"status":"authorized"}`.

**O que reparar:** enquanto o canal faz esse loop, o aplicativo resolve o
desafio por fora, pelo deeplink, com uma sessão de jornada própria. O sidecar
não participa dessa parte.

E a sessão emitida pelo provedor **não vem na resposta** — é credencial, e fica
no sidecar.

**Se ficar em polling para sempre:** o cenário do gateway simulado travou.
`docker compose restart gateway-pdc` reinicia do começo.

---

# Grupo 3 — A jornada do CIAM (embarque de fator)

## 3.1 A mesma imagem dispara outra jornada

**O que se verifica:** o componente é o mesmo; o que muda é a configuração da
instância.

```powershell
curl.exe -i -X POST localhost:18083/api/v1/fatores/dispositivo `
  -H "x-canal-autenticacao: eyJhbGciOiJIUzI1NiJ9.jwt-do-cognito.assinatura" `
  -H "Content-Type: application/json" -d '{}'
```

**Esperado:** `401` com desafio de biometria — não deeplink:

```json
{
  "status": "challenge_required",
  "callbacks": [
    { "type": "NameCallback",
      "output": [
        { "name": "prompt", "value": "CHALLENGE_REQUIRED" },
        { "name": "defaultValue", "value": "BIOMETRIA:UNICO" }
      ] }
  ]
}
```

**O que reparar — e este é o cenário mais importante do conjunto:** mesma
imagem, mesmo código, mesma matriz de configuração. Um canal novo entra sem
alterar o componente.

---

## 3.2 O verbo muda o desfecho no mesmo endereço

**O que se verifica:** a matriz distingue método, não só endereço.

```powershell
curl.exe -s -o NUL -w "GET    fatores: %{http_code}`n" localhost:18083/api/v1/fatores/dispositivo
curl.exe -s -o NUL -w "DELETE fatores: %{http_code}`n" -X DELETE localhost:18083/api/v1/fatores/dispositivo -H "x-canal-autenticacao: eyJ.jwt.x"
```

**Esperado:**

```
GET    fatores: 200
DELETE fatores: 401
```

**Por que isso importa:** ver quais fatores estão ativos é consulta, e o cliente
precisa disso para gerenciar os dispositivos. Remover um derruba a proteção — e
sem confirmação, bastaria remover para contornar tudo.

Se a matriz fosse indexada só por endereço, seria preciso escolher entre
proteger os dois (fricção na consulta) ou nenhum (brecha na remoção).

---

# Grupo 4 — Tentativas de contornar

## 4.1 Trocar a grafia do endereço

**O que se verifica:** as formas abaixo chegam ao mesmo lugar no BFF, e todas
continuam sendo barradas.

```powershell
curl.exe -s -o NUL -w "barra final: %{http_code}`n" "localhost:18080/api/v1/clientes/dados-cadastrais/" -H "x-canal-autenticacao: eyJ.jwt.x"
curl.exe -s -o NUL -w "barra dupla: %{http_code}`n" "localhost:18080/api//v1/clientes/dados-cadastrais" -H "x-canal-autenticacao: eyJ.jwt.x"
curl.exe -s -o NUL -w "codificado:  %{http_code}`n" "localhost:18080/api/v1/%63lientes/dados-cadastrais" -H "x-canal-autenticacao: eyJ.jwt.x"
```

**Esperado:** `401` nas três.

**O que reparar:** `%63` é a letra `c`. O sidecar decodifica e normaliza o
endereço antes de consultar a matriz — senão bastaria trocar a grafia para
passar por cima da proteção.

---

## 4.2 Endereço suspeito morre

**O que se verifica:** navegação de diretório e separador codificado são
recusados, não corrigidos.

```powershell
curl.exe -s -o NUL -w "navegacao: %{http_code}`n" --path-as-is "localhost:18080/api/v1/clientes/../clientes/dados-cadastrais"
curl.exe -s -o NUL -w "separador: %{http_code}`n" "localhost:18080/api%2Fv1/clientes/dados-cadastrais"
```

**Esperado:** `400` nas duas.

**Por que recusar em vez de corrigir:** resolver o `..` significaria decidir
sobre um endereço e encaminhar outro. Bastaria essa divergência para contornar a
matriz.

**Nota:** o `--path-as-is` é necessário no primeiro comando. Sem ele, o próprio
curl resolve o `..` antes de enviar, e o cenário não testa nada.

---

## 4.3 O header forjado não passa

**O que se verifica:** o canal tenta declarar por conta própria que a
confirmação aconteceu.

```powershell
curl.exe -s localhost:18080/api/v1/clientes/extrato -H "x-sidecar-verified: true" | Select-String "sidecar-verified"
```

**Esperado: nenhuma saída.**

Para ver por inteiro, sem o filtro:

```powershell
curl.exe -s localhost:18080/api/v1/clientes/extrato -H "x-sidecar-verified: true"
```

O cabeçalho **não** aparece na lista que o BFF recebeu.

**Por que isso é o cenário mais importante em termos de segurança:** se o
cabeçalho atravessasse, qualquer um poderia declarar a confirmação como feita, e
o BFF acreditaria. É o que separa "o componente confia no canal" de "não
confia".

---

# Grupo 5 — Comportamento sob falha

## 5.1 Falha de dependência não libera

**O que se verifica:** quando a confirmação não pode acontecer, a operação não
passa.

```powershell
docker compose stop gateway-pdc

curl.exe -i localhost:18080/api/v1/clientes/dados-cadastrais `
  -H "x-canal-autenticacao: eyJ.jwt.x"

docker compose start gateway-pdc
```

**Esperado:** `503` com `authorization_unavailable`, e **a requisição não
alcançou o BFF**.

**O que reparar:** o status diz indisponibilidade, não recusa. A distinção
importa: o canal mostra mensagem diferente, e ninguém é acordado de madrugada
por uma biometria reprovada.

---

## 5.2 Rota protegida sem token do canal

**O que se verifica:** sem quem autenticar, o sidecar recusa antes de chamar o
provedor.

```powershell
curl.exe -i localhost:18080/api/v1/clientes/dados-cadastrais
```

**Esperado:** `401` com `session_required`.

**O que reparar:** nenhuma chamada ao gateway foi feita. Confirmar no log:

```powershell
docker compose logs sidecar-pdc | Select-String "sem token do canal"
```

---

# Grupo 6 — Rastreabilidade

## 6.1 O log conta a jornada sem vazar credencial

```powershell
docker compose logs sidecar-pdc  | Select-String "jornada|desafio"
docker compose logs sidecar-ciam | Select-String "jornada|desafio"
```

**Esperado, PDC:**
```
Iniciando a jornada 'pdc-bank-authz-consultivo' no realm 'alpha'
Passo de início recebeu desafio: PollingWaitCallback(waitTime=8000), ConfirmationCallback
```

**Esperado, CIAM:**
```
Iniciando a jornada 'factor-onboarding' no realm 'alpha'
Passo de início recebeu desafio: NameCallback(prompt=CHALLENGE_REQUIRED defaultValue=BIOMETRIA:UNICO)
```

**O que reparar — e vale demonstrar com atenção:**

O `PollingWaitCallback` mostra o `waitTime`, **mas não o deeplink**. E o
`TextOutputCallback` do embarque, quando aparecer, vem sem detalhe nenhum — é
onde trafega a semente do autenticador.

Isso não é acaso: o registro usa **lista de permitidos**. Só `prompt`,
`waitTime` e `defaultValue` entram. Um passo novo que traga um campo sensível
fica de fora por padrão, sem ninguém precisar lembrar de excluí-lo.

**O que nunca aparece, em nível nenhum:** token do canal, identificador de
jornada, sessão emitida, captura biométrica, semente do autenticador, código
digitado, endereço cru.

---

## 6.2 Correlação entre chamado e log

```powershell
curl.exe -i localhost:18080/api/v1/clientes/extrato -H "X-Correlation-Id: chamado-4711"
docker compose logs sidecar-pdc | Select-String "chamado-4711"
```

**Esperado:** o mesmo valor de volta no cabeçalho da resposta, e as linhas da
requisição no log com `[chamado-4711]`.

**Por que existe:** liga um chamado de suporte a uma linha de log. Sem isso, a
investigação começa por horário aproximado.

---

# Resumo

| # | Cenário | Esperado |
|---|---------|----------|
| 1.1 | Rota comum | `200` do BFF |
| 1.2 | Corpo e query | íntegros |
| 2.1 | Consulta sensível do PDC | `401` com deeplink |
| 2.2 | Polling | `401`, `401`, `200` |
| 3.1 | Rota do CIAM | `401` com biometria |
| 3.2 | GET vs DELETE | `200` e `401` |
| 4.1 | Grafia diferente | `401` nas três |
| 4.2 | Endereço suspeito | `400` nas duas |
| 4.3 | Header forjado | não chega ao BFF |
| 5.1 | Gateway fora | `503`, sem alcançar o BFF |
| 5.2 | Sem token | `401`, sem chamar o gateway |
| 6.1 | Log da jornada | rastreável, sem credencial |
| 6.2 | Correlação | mesmo valor no log |

---

# O que estes cenários não cobrem

Vale registrar, para não parecer que o componente está completo:

**A consulta de autorização.** O sidecar dispara a jornada, mas ainda não
pergunta ao autorizador se a transação já foi autorizada. É o que fecha o ciclo
quando o canal reapresenta a requisição depois de confirmar.

**O Token Handler.** O token obtido ao fim da jornada não tem destino definido.

**A jornada real.** O gateway destes cenários é simulado. Contra o ambiente de
homologação, apenas o primeiro passo foi exercitado.

**Carga.** Não houve teste de volume. O polling do fluxo consultivo repete a
cada 8 segundos, e o impacto disso no dimensionamento não foi medido.

---

# Encerrando

```powershell
docker compose down
```

### Repetir os cenários

O gateway simulado guarda o estado da jornada:

```powershell
docker compose restart gateway-pdc gateway-ciam
```

### Se algo divergir

| Sintoma | Causa provável |
|---------|----------------|
| **`200` onde se esperava `401`** | **A rota não está na matriz — o caso mais perigoso.** Conferir o log de boot |
| `401` onde se esperava `200` | A rota está na matriz |
| `503` na rota protegida | O gateway simulado não subiu |
| `502` em tudo | O BFF não subiu |
| Polling não avança | `docker compose restart gateway-pdc` |
| `..` não dá `400` | Faltou `--path-as-is` |
| `curl` reclama de sintaxe | Use `curl.exe` |