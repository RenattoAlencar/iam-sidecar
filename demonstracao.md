# Roteiro da demonstração

Sobe o sidecar, o BFF e um gateway falso em containers, e percorre uma jornada
completa. Comandos em PowerShell.

---

## Antes de começar

```powershell
mvn clean package -DskipTests
cd demo
docker compose down -v
docker compose up -d --build
```

O `clean` e o `--build` importam: sem eles o Docker reaproveita a camada
anterior e sobe com o jar antigo.

**Espere ficar pronto** — o build da imagem leva alguns minutos na primeira vez:

```powershell
docker compose ps
```

O sidecar precisa aparecer como `healthy`.

### Conferir a matriz antes de qualquer teste

```powershell
docker compose logs sidecar | Select-String "Matriz de interceptação" -Context 0,4
```

Esperado: **3 regras**. Se não for, a configuração não chegou e todo o resto vai
enganar.

---

## O que sobe

```
   você (canal) ──► SIDECAR :8080 ──┬──► BFF :8081          rota comum
                    porta 18080     │
                                    └──► GATEWAY :8082      rota protegida
```

Os três compartilham o namespace de rede, como num pod. **Só o sidecar é
alcançável de fora.**

O gateway falso responde a jornada em três passos, com os mesmos tipos de
callback do contrato real: confirmação, espera pela análise, conclusão.

---

## 1. O sidecar é invisível no tráfego comum

```powershell
curl.exe -i localhost:18080/api/v1/conta/extrato
```

**Esperado:** `200` com a resposta do BFF — corpo grande, com `path` e `headers`.

**O que dizer:** rota fora da matriz atravessa sem nenhuma verificação. O canal
não percebe que existe um salto a mais.

---

## 2. A rota protegida dispara a jornada

```powershell
curl.exe -i -X POST localhost:18080/api/v1/pix/transferencia `
  -H "x-canal-autenticacao: eyJhbGciOiJIUzI1NiJ9.token-do-canal.assinatura" `
  -H "Content-Type: application/json" `
  -d '{\"chave\":\"fulano@banco\",\"valor\":50}'
```

**Esperado:** `401` com o desafio:

```json
{
  "status": "challenge_required",
  "authId": "jornada-da-demonstracao-passo-1",
  "callbacks": [
    { "type": "NameCallback",
      "output": [
        { "name": "prompt", "value": "CHALLENGE_REQUIRED" },
        { "name": "defaultValue", "value": "BIOMETRIA:UNICO" }
      ],
      "input": [ { "name": "IDToken1", "value": "BIOMETRIA:UNICO" } ] }
  ],
  "correlationId": "..."
}
```

**O que dizer:** a transferência **não foi executada**. Repare que não há
resposta do BFF no corpo — a requisição morreu no sidecar. O canal recebeu o
desafio para apresentar ao cliente.

---

## 3. O canal responde o desafio

Aqui o canal enviaria a captura biométrica. Devolve os callbacks como recebeu,
com o campo preenchido:

```powershell
curl.exe -i -X POST localhost:18080/ciam/challenge `
  -H "Content-Type: application/json" `
  -d '{\"authId\":\"jornada-da-demonstracao-passo-1\",\"callbacks\":[{\"type\":\"NameCallback\",\"input\":[{\"name\":\"IDToken1\",\"value\":\"{\\\"foto\\\":\\\"<captura>\\\",\\\"channel\\\":\\\"APP\\\"}\"}]}]}'
```

**Esperado:** `401` com o passo de espera:

```json
{
  "status": "challenge_required",
  "authId": "jornada-da-demonstracao-passo-2",
  "callbacks": [
    { "type": "PollingWaitCallback",
      "output": [ { "name": "waitTime", "value": "3000" } ] }
  ]
}
```

**O que dizer:** o serviço de biometria está analisando. O canal aguarda o tempo
indicado e reenvia o corpo inalterado — é o polling.

---

## 4. O polling conclui a jornada

```powershell
curl.exe -i -X POST localhost:18080/ciam/challenge `
  -H "Content-Type: application/json" `
  -d '{\"authId\":\"jornada-da-demonstracao-passo-2\",\"callbacks\":[{\"type\":\"PollingWaitCallback\"}]}'
```

**Esperado:** `200`

```json
{ "status": "authorized", "correlationId": "..." }
```

**O que dizer:** a jornada concluiu. Repare que **a sessão emitida pelo gateway
não veio na resposta** — ela é credencial e fica no sidecar.

---

## 5. O log conta a jornada inteira

```powershell
docker compose logs sidecar | Select-String "Rota interceptada|jornada|desafio|Token"
```

**Esperado:**

```
Rota interceptada: regra=pix-transfer, método=POST
Iniciando a jornada 'factor-onboarding' no realm 'alpha'
Passo de início recebeu desafio: NameCallback(prompt=CHALLENGE_REQUIRED defaultValue=BIOMETRIA:UNICO)
Desafio emitido ao canal: regra=pix-transfer, callbacks=1
Continuando a jornada: respondendo NameCallback(prompt=CHALLENGE_REQUIRED)
Passo de continuação recebeu desafio: PollingWaitCallback(waitTime=3000)
Jornada concluída no passo de continuação: sessão emitida pelo gateway
```

**O que dizer — e este é o ponto mais forte:** dá para acompanhar cada fator
passando, mas **nada sensível está ali**. A captura biométrica, a semente do
autenticador e o código digitado ficam de fora por lista de permitidos: só
`prompt`, `waitTime` e `defaultValue` entram no log.

---

## 6. O verbo muda o desfecho no mesmo endereço

```powershell
curl.exe -s -o NUL -w "GET  chaves: %{http_code}`n" localhost:18080/api/v1/pix/chaves
curl.exe -s -o NUL -w "POST chaves: %{http_code}`n" -X POST localhost:18080/api/v1/pix/chaves -H "x-canal-autenticacao: eyJ.token.x"
```

**Esperado:** `200` e `401`

**O que dizer:** listar as próprias chaves é consulta. Cadastrar uma nova
redireciona dinheiro na próxima transferência — por isso só o POST está na
matriz.

---

## 7. Trocar a grafia não contorna a proteção

```powershell
curl.exe -s -o NUL -w "barra final: %{http_code}`n" -X POST "localhost:18080/api/v1/pix/transferencia/" -H "x-canal-autenticacao: eyJ.token.x"
curl.exe -s -o NUL -w "barra dupla: %{http_code}`n" -X POST "localhost:18080/api//v1/pix/transferencia" -H "x-canal-autenticacao: eyJ.token.x"
curl.exe -s -o NUL -w "codificado:  %{http_code}`n" -X POST "localhost:18080/api/v1/%70ix/transferencia" -H "x-canal-autenticacao: eyJ.token.x"
```

**Esperado:** `401` nas três.

**O que dizer:** as três chegariam ao mesmo lugar no BFF. O sidecar normaliza o
endereço antes de consultar a matriz — `%70` é a letra `p`.

---

## 8. Endereço suspeito morre

```powershell
curl.exe -s -o NUL -w "navegacao: %{http_code}`n" --path-as-is -X POST "localhost:18080/api/v1/pix/../pix/transferencia"
curl.exe -s -o NUL -w "separador: %{http_code}`n" -X POST "localhost:18080/api%2Fv1/pix/transferencia"
```

**Esperado:** `400` nas duas.

**O que dizer:** o sidecar recusa em vez de corrigir. Resolver o `..` e
encaminhar significaria decidir sobre um endereço e enviar outro — bastaria essa
divergência para passar por cima da matriz.

---

## 9. O header forjado não passa

```powershell
curl.exe -s localhost:18080/api/v1/conta/extrato -H "x-sidecar-verified: true" | Select-String "sidecar-verified"
```

**Esperado: nenhuma saída.**

**O que dizer:** o canal tentou enviar o header que só o sidecar deveria
escrever — o que declararia a confirmação como já feita. O sidecar descartou
antes de encaminhar. É o que separa "confia no canal" de "não confia".

---

## 10. Falha de dependência não libera

Derrube o gateway e tente a rota protegida:

```powershell
docker compose stop gateway
curl.exe -i -X POST localhost:18080/api/v1/pix/transferencia -H "x-canal-autenticacao: eyJ.token.x"
docker compose start gateway
```

**Esperado:** `503` com `authorization_unavailable` — e **a transferência não
foi executada**.

**O que dizer:** fail-closed. Quando a confirmação não pode acontecer, a
operação não passa. E o status diz que é indisponibilidade, não recusa — o canal
mostra mensagem diferente, e ninguém é acordado por uma biometria reprovada.

---

## Resumo para fechar

| # | Demonstra |
|---|-----------|
| 1 | invisível no tráfego comum |
| 2-4 | jornada completa, do desafio à conclusão |
| 5 | log rastreável sem vazar credencial |
| 6 | o verbo importa, não só o endereço |
| 7-8 | grafia diferente não contorna |
| 9 | o canal não forja o resultado |
| 10 | falha de dependência não libera |

---

## Encerrando

```powershell
docker compose down
```

## Se algo travar

| Sintoma | O que fazer |
|---------|-------------|
| `docker compose up` parece travado | é o build da imagem; acompanhe com `docker compose build` separado |
| O sidecar não fica `healthy` | `docker compose logs sidecar` — geralmente validação de configuração |
| Tudo responde `502` | o BFF não subiu: `docker compose logs bff` |
| A rota protegida dá `503` | o gateway falso não subiu: `docker compose logs gateway` |
| A jornada não avança | o estado do cenário ficou parado: `docker compose restart gateway` reinicia do começo |
| Porta em uso | execução local ainda rodando, ou container anterior de pé |
| `curl` reclama de sintaxe | use `curl.exe` |

### Reiniciar a jornada do começo

O gateway falso guarda o estado do cenário. Para repetir a demonstração:

```powershell
docker compose restart gateway
```