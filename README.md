# Validação do sidecar

Roteiro para verificar, em container, se o sidecar está fazendo o que deveria:
deixar passar o tráfego comum e barrar as rotas sensíveis antes que cheguem à
aplicação de negócio.

Comandos em **PowerShell**.

---

## As quatro peças

| Peça | O que é |
|------|---------|
| **Canal** | Quem chama a API — o app do cliente. Aqui, é você digitando `curl.exe` |
| **Sidecar** | O que estamos validando. Recebe tudo e decide o que passa |
| **BFF** | A aplicação de negócio que o sidecar protege. Em produção é o BFF transacional; aqui é substituído por um **eco** |
| **Matriz** | A lista de rotas que exigem confirmação do cliente |

```
   você (canal) ──► SIDECAR :8080 ──► BFF :8081
                    porta 18080        só alcançável
                    no seu micro       pelo sidecar
```

### O que é o "eco"

O BFF desta validação não executa transferência nenhuma. Ele **devolve a
requisição como a recebeu**, em JSON — método, path, headers e corpo.

Serve para uma coisa só: provar o que chegou do outro lado.

| A resposta tem | Quem respondeu |
|----------------|----------------|
| `path`, `headers`, `method` e `x-powered-by` | **BFF** — a requisição atravessou |
| `error` e `correlationId`, corpo curto | **sidecar** — a requisição morreu ali |

Nos testes de recusa é isso que importa: se aparecer eco, a requisição chegou ao
BFF quando não deveria.

### O que é a "matriz"

A lista de rotas que exigem confirmação. Cada linha tem endereço e verbo:

```yaml
- name: pix-transfer
  path: /api/v1/pix/transferencia
  methods: [POST]
```

**Toda requisição passa pelo sidecar** — ele é a porta de entrada. A matriz não
decide o que chega até ele; decide o que ele **verifica**.

- rota **na** matriz → verificada
- rota **fora** da matriz → repassada ao BFF sem checagem

Por isso uma rota sensível esquecida na matriz responde `200` normalmente, sem
erro nem alerta. É o motivo do passo zero abaixo.

---

## Subindo

```powershell
mvn clean package -DskipTests
cd demo
docker compose down
docker compose up -d --build
```

### Passo zero — conferir a matriz

```powershell
docker compose logs sidecar | Select-String "Matriz de interceptação" -Context 0,7
```

Esperado: **5 regras**.

```
Matriz de interceptação com 5 regra(s):
  pix-transfer [[POST] /api/v1/pix/transferencia]
  ted-transfer [[POST] /api/v1/transferencias/ted]
  boleto-payment [[POST] /api/v2/pagamentos/boletos/pagamento]
  pix-keys-register [[POST] /api/v1/pix/chaves]
  limites-consulta [[GET] /api/v1/conta/limites/transacionais]
```

Se não vier nada, as linhas de boot saíram do buffer:
`docker compose logs sidecar | Select-Object -First 60`

**Se não forem 5 regras, pare aqui.** A configuração não chegou, e todos os
testes seguintes vão enganar.

---

## Os testes

Use `curl.exe`, não `curl` — no PowerShell, `curl` é outro comando.

### 1. A aplicação está de pé

```powershell
curl.exe -s localhost:18090/actuator/health
```

Esperado: `{"status":"UP",...}`

---

### 2. Rota comum atravessa

```powershell
curl.exe -i localhost:18080/api/v1/conta/extrato
```

Esperado: `200` com o eco do BFF — corpo grande, com `path` e `headers`, e o
header `x-powered-by: Express`.

**Prova que:** o sidecar é invisível em rota fora da matriz.

> No eco, `"host": "127.0.0.1:8081"` é o endereço do **BFF**. O sidecar reescreve
> esse header para o destino real; o endereço original fica em
> `x-forwarded-host`. É o comportamento normal de um proxy reverso.

---

### 3. Query e corpo chegam íntegros

```powershell
curl.exe -i "localhost:18080/api/v1/conta/extrato?de=2026-01-01&ate=2026-01-31" -H "Content-Type: application/json" -d '{\"descricao\":\"teste\"}'
```

Esperado: no eco, `query` com os dois parâmetros e `body` com o conteúdo.

**Prova que:** o sidecar não interpreta nem altera o que o canal envia.

---

### 4. Rota da matriz é barrada

```powershell
curl.exe -i -X POST localhost:18080/api/v1/pix/transferencia -H "Content-Type: application/json" -d '{\"valor\":50}'
```

Esperado: `401`, corpo curto:

```json
{"error":"confirmation_required","correlationId":"kQ8x..."}
```

**Prova que:** a requisição morreu no sidecar. Repare que **não há eco** — sem
`path`, sem `x-powered-by`. Se aparecesse, teria chegado ao BFF.

---

### 5. O verbo muda o desfecho

```powershell
curl.exe -s -o NUL -w "GET  chaves: %{http_code}`n" localhost:18080/api/v1/pix/chaves
curl.exe -s -o NUL -w "POST chaves: %{http_code}`n" -X POST localhost:18080/api/v1/pix/chaves
```

Esperado: `200` e `401`.

**Prova que:** a matriz distingue método. Listar as próprias chaves é consulta;
cadastrar uma nova redireciona dinheiro depois.

---

### 6. GET também pode ser barrado

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" localhost:18080/api/v1/conta/limites/transacionais
```

Esperado: `401`

**Prova que:** o critério é a operação, não o verbo.

---

### 7. Trocar a grafia não contorna

```powershell
curl.exe -s -o NUL -w "barra final: %{http_code}`n" -X POST "localhost:18080/api/v1/pix/transferencia/"
curl.exe -s -o NUL -w "barra dupla: %{http_code}`n" -X POST "localhost:18080/api//v1/pix/transferencia"
curl.exe -s -o NUL -w "codificado:  %{http_code}`n" -X POST "localhost:18080/api/v1/%70ix/transferencia"
```

Esperado: `401` nas três.

**Prova que:** as três formas chegariam ao mesmo lugar no BFF, e todas
continuam barradas. (`%70` é a letra `p`.)

---

### 8. Path suspeito é recusado

```powershell
curl.exe -s -o NUL -w "navegacao: %{http_code}`n" --path-as-is -X POST "localhost:18080/api/v1/pix/../pix/transferencia"
curl.exe -s -o NUL -w "separador: %{http_code}`n" -X POST "localhost:18080/api%2Fv1/pix/transferencia"
```

Esperado: `400` nas duas.

**Prova que:** path malformado morre, em vez de ser corrigido e encaminhado.

> O `--path-as-is` é necessário: sem ele o próprio curl resolve o `..` antes de
> enviar, e o teste não testa nada.

---

### 9. Header forjado não passa

```powershell
curl.exe -s localhost:18080/api/v1/conta/extrato -H "x-sidecar-verified: true" | Select-String "sidecar-verified"
```

Esperado: **nenhuma saída**.

**Prova que:** o canal tentou enviar o header que o sidecar reserva para si — o
que declararia a confirmação como já feita — e o sidecar descartou antes de
encaminhar.

É o teste mais importante do conjunto.

---

### 10. IP do cliente chega correto

```powershell
curl.exe -s localhost:18080/api/v1/conta/extrato -H "X-Forwarded-For: 203.0.113.9" | Select-String "forwarded-for"
```

Esperado: **um único valor** — `"x-forwarded-for": "203.0.113.9, 172.18.0.1"`

**Prova que:** a cadeia chega completa e sem duplicação. Dois valores fariam o
BFF ler o IP errado, que é o dado usado em investigação de fraude.

---

### 11. Corpo grande demais é recusado

```powershell
[System.IO.File]::WriteAllText("$PWD\big.txt", ("x" * 2000))
curl.exe -s -o NUL -w "%{http_code}`n" -X POST localhost:18080/api/v1/conta/busca --data-binary "@big.txt"
```

Esperado: `413` (o teto nesta validação é 1 KiB).

**Prova que:** o sidecar protege a memória do pod, que ele divide com o BFF.

---

### 12. Rastreabilidade

```powershell
curl.exe -i localhost:18080/api/v1/conta/extrato -H "X-Correlation-Id: chamado-4711"
docker compose logs sidecar | Select-String "chamado-4711"
```

Esperado: o mesmo valor de volta no header, e as linhas da requisição no log com
`[chamado-4711]`.

**Prova que:** dá para ligar um chamado de suporte a uma linha de log.

---

## Resumo

| # | Teste | Na matriz? | Esperado |
|---|-------|-----------|----------|
| 1 | Saúde | — | `200` `UP` |
| 2 | Rota comum | não | `200` com eco |
| 3 | Query e corpo | não | íntegros no eco |
| 4 | Rota sensível | **sim** | `401` **sem** eco |
| 5 | GET vs POST | GET não, POST sim | `200` e `401` |
| 6 | GET sensível | **sim** | `401` |
| 7 | Grafia diferente | **sim** | `401` nas três |
| 8 | Path suspeito | — | `400` nas duas |
| 9 | Header forjado | não | não chega ao BFF |
| 10 | IP do cliente | não | um valor só |
| 11 | Corpo grande | não | `413` |
| 12 | Correlação | não | valor no log |

---

## Encerrando

```powershell
docker compose down
```

## Se algo não funcionar

| Sintoma | Causa provável |
|---------|----------------|
| **`200` onde se esperava `401`** | **A rota não está na matriz — o caso mais perigoso.** Conferir o passo zero |
| `401` onde se esperava `200` | A rota está na matriz; conferir o passo zero |
| Tudo responde `502` | O BFF de eco não subiu — `docker compose logs bff` |
| O sidecar não sobe, log fala em loopback | `target` apontando para nome de serviço em vez de `127.0.0.1` |
| O boot falha citando uma propriedade | Regra sem path ou sem método, ou duas regras iguais |
| O log só mostra healthcheck | Boot saiu do buffer — `Select-Object -First 60` |
| Porta em uso | Sidecar rodando local, ou container anterior não derrubado |
| O `..` não dá `400` | Faltou `--path-as-is` |
| `curl` reclama de sintaxe | Use `curl.exe` |