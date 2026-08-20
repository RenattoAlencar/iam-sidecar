# Validação do proxy em container

Sobe o sidecar e um BFF de eco em containers que compartilham o namespace de
rede, e exercita as rotas antes de existir qualquer chamada ao gateway de
identidade.

O BFF **devolve a requisição como a recebeu**. É essa escolha que faz a
validação valer: um proxy pode responder `200` e ter entregue ao BFF uma
requisição com header forjado. Sem o eco, ninguém veria a diferença.

---

## Subindo

```bash
mvn clean package -DskipTests
cd demo
docker compose down
docker compose up -d --build
```

O `clean` e o `--build` importam. Sem eles, o Docker reaproveita a camada
anterior e sobe com o jar antigo — e você acaba depurando um código que não está
rodando.

Pronto quando aparecer, no log:

```
Sidecar encaminhando para http://127.0.0.1:8081 ...
Matriz de interceptação com 5 regra(s):
  pix-transfer [[POST] /api/v1/pix/transferencia]
  ...
Todo path fora da matriz é encaminhado ao BFF sem verificação.
```

**Conferir o número de regras antes de qualquer teste.** Se não for 5, a
configuração não chegou e todo o resto vai enganar.

### A parte do Compose que mais importa

```yaml
bff:
  network_mode: "service:sidecar"
```

Num pod, os containers compartilham o namespace de rede, e é isso que faz
`127.0.0.1:8081` funcionar entre eles. Sem essa linha, o BFF teria endereço
próprio e a validação seria de uma topologia que não é a de produção.

Repare que **o BFF não expõe porta nenhuma**. Só o sidecar é alcançável de fora.
Não existe caminho alternativo.

---

## Os testes

No PowerShell use `curl.exe`, não `curl` — o apelido do PowerShell aponta para
outro comando, com sintaxe diferente.

### 1. Saúde

```bash
curl -s localhost:18090/actuator/health
```

Esperado: `{"status":"UP",...}`

### 2. Proxy transparente

```bash
curl -i localhost:18080/api/v1/conta/extrato
```

Esperado: `200`, corpo JSON grande com `path`, `headers`, `method`, e o header
`X-Correlation-Id` na resposta. O `x-powered-by: Express` confirma que veio do
eco.

### 3. Query e corpo atravessam íntegros

```bash
curl -i "localhost:18080/api/v1/conta/extrato?de=2026-01-01&ate=2026-01-31" \
  -H "Content-Type: application/json" \
  -d '{"descricao":"acentuação"}'
```

Esperado: no eco, `query` com os dois parâmetros e `body` com a acentuação
intacta.

### 4. A matriz agindo

```bash
curl -i -X POST localhost:18080/api/v1/pix/transferencia
```

Esperado: `401`, corpo pequeno com `confirmation_required` e `correlationId`.

**Sem `path` e sem `x-powered-by`** — a ausência do eco é a prova de que a
requisição morreu no sidecar.

### 5. O verbo muda o desfecho

```bash
curl -s -o /dev/null -w "GET  chaves: %{http_code}\n"  localhost:18080/api/v1/pix/chaves
curl -s -o /dev/null -w "POST chaves: %{http_code}\n" -X POST localhost:18080/api/v1/pix/chaves
```

Esperado: `200` e `401`. Mesmo endereço, verbos diferentes.

### 6. GET interceptado

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:18080/api/v1/conta/limites/transacionais
```

Esperado: `401`. Confirma que interceptar não é sobre o verbo — é sobre a
operação.

### 7. Variação de escrita não contorna

```bash
curl -s -o /dev/null -w "barra final:    %{http_code}\n" -X POST "localhost:18080/api/v1/pix/transferencia/"
curl -s -o /dev/null -w "barra dupla:    %{http_code}\n" -X POST "localhost:18080/api//v1/pix/transferencia"
curl -s -o /dev/null -w "percent:        %{http_code}\n" -X POST "localhost:18080/api/v1/%70ix/transferencia"
```

Esperado: `401` nos três. Todas chegam ao mesmo handler no BFF, e todas
continuam sendo interceptadas.

### 8. Path suspeito morre

```bash
curl -s -o /dev/null -w "navegacao:  %{http_code}\n" --path-as-is -X POST "localhost:18080/api/v1/pix/../pix/transferencia"
curl -s -o /dev/null -w "separador:  %{http_code}\n" -X POST "localhost:18080/api%2Fv1/pix/transferencia"
```

Esperado: `400` nos dois. O `--path-as-is` impede o próprio curl de resolver o
`..` antes de enviar.

### 9. O header forjado — o mais importante

```bash
curl -s localhost:18080/api/v1/conta/extrato -H "x-sidecar-verified: true" | grep -i stepup
```

Esperado: **nenhuma saída**. O header não chegou ao BFF.

Sem o `grep`, dá para ver o JSON inteiro e conferir que dentro de `headers` estão
`host`, `accept`, `x-forwarded-*` — e não o `x-sidecar-verified`.

É a diferença visível entre confiar e não confiar no canal.

### 10. Cadeia de encaminhamento

```bash
curl -s localhost:18080/api/v1/conta/extrato -H "X-Forwarded-For: 203.0.113.9" | grep -i forwarded-for
```

Esperado: **um único valor**, no formato `203.0.113.9, <ip-do-cliente>`. Se
aparecerem dois, o header saiu duplicado e o BFF leria o IP errado — que é o
dado usado em investigação de fraude.

### 11. Corpo acima do teto

```bash
head -c 2000 /dev/zero | tr '\0' 'x' > big.txt
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:18080/api/v1/conta/busca --data-binary "@big.txt"
```

Esperado: `413`. O teto está em 1 KiB nesta configuração.

### 12. Correlação

```bash
curl -i localhost:18080/api/v1/conta/extrato -H "X-Correlation-Id: chamado-4711"
```

Esperado: o mesmo valor de volta no header. E no log do container:

```bash
docker compose logs sidecar | grep chamado-4711
```

---

## Encerrando

```bash
docker compose down
```

## Quando algo não funciona

| Sintoma | Causa provável |
|---------|----------------|
| O sidecar não sobe e o log fala em loopback | `target` apontando para nome de serviço em vez de `127.0.0.1` |
| Tudo responde `502` | O BFF de eco não subiu — `docker compose logs bff`, e conferir se a variável é `HTTP_PORT` |
| O boot falha citando uma propriedade | Regra sem path ou sem método, ou duas regras com mesmo método e path |
| `401` onde se esperava `200` | A rota está na matriz; conferir a matriz efetiva no log de boot |
| `200` onde se esperava `401` | A rota **não** está na matriz — o caso mais perigoso. Conferir o número de regras no log |
| Porta em uso ao subir | Execução local do sidecar ainda rodando na 8080, ou container anterior não derrubado |