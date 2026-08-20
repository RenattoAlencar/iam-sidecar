# Validação do sidecar em container

Este roteiro valida o comportamento do **sidecar** como proxy, antes de existir
qualquer chamada de autenticação. O objetivo é responder três perguntas:

1. O sidecar é invisível quando não precisa agir?
2. Ele barra o que a matriz manda barrar — e barra **antes** de a requisição
   chegar ao BFF?
3. Ele impede que o canal influencie o resultado?

---

## Vocabulário

Cinco termos aparecem o tempo todo — no código, no log e neste roteiro. Vale
fixar o significado antes de começar, porque eles não são intercambiáveis.

### Canal

Quem consome a API: o aplicativo do cliente, o site, ou qualquer sistema que
chame as rotas de negócio.

Nesta validação, **o canal é você digitando `curl`**.

Um ponto que importa: o canal é a parte em quem o sidecar **não confia**. Ele
pode enviar qualquer header, qualquer corpo, qualquer grafia de path. Vários
testes abaixo existem justamente para verificar que essa desconfiança está
implementada.

### Sidecar

O componente que estamos validando. Um proxy que fica na frente do BFF e decide,
para cada requisição, se ela segue ou morre ali.

Ele roda **no mesmo pod** que o BFF, e recebe todo o tráfego do canal. É a porta
de entrada.

### BFF

A aplicação de negócio que o sidecar protege — *Backend for Frontend*. Em
produção é ela que executa a transferência, o pagamento, a consulta.

O BFF escuta apenas em `127.0.0.1`. Isso não é detalhe de configuração: é o que
torna o sidecar incontornável. Se o BFF fosse alcançável de fora do pod, qualquer
um poderia falar direto com ele e a proteção viraria decoração.

### Matriz de interceptação

**A lista de quais rotas exigem confirmação do cliente.** É a configuração que
diz ao sidecar o que barrar.

Cada entrada tem três campos:

```yaml
- name: pix-transfer                        # rótulo para log e métrica
  path: /api/v1/pix/transferencia           # o endereço
  methods: [POST]                           # quais verbos
```

Três coisas sobre a matriz que explicam vários testes:

**O método faz parte da chave.** `GET /api/v1/pix/chaves` e
`POST /api/v1/pix/chaves` são entradas diferentes. Listar as próprias chaves é
consulta; cadastrar uma nova redireciona dinheiro depois.

**Ausência significa desprotegido, não ausente.** Todo o tráfego passa pelo
sidecar de qualquer forma — ele é a porta do pod. O que a matriz decide é o que
ele *verifica*. Rota que não está na matriz é encaminhada ao BFF sem nenhuma
checagem.

**Por isso o log de boot lista a matriz efetiva**, e por isso o passo zero deste
roteiro é conferir o número de regras. Uma rota que deveria estar e não está
responde `200` como se tudo estivesse certo.

### Eco (o BFF desta validação)

O BFF que sobe aqui não faz nada de negócio. Ele é um **servidor de eco**:
devolve, em JSON, a requisição exatamente como a recebeu — método, path, query,
headers e corpo.

Uma chamada a ele responde assim:

```bash
curl -s localhost:18080/api/v1/conta/extrato
```

```json
{
  "method": "GET",
  "path": "/api/v1/conta/extrato",
  "query": {},
  "headers": {
    "host": "127.0.0.1:8081",
    "accept": "*/*",
    "user-agent": "curl/8.4.0",
    "x-forwarded-for": "172.18.0.1"
  },
  "body": ""
}
```

**Por que um eco, e não um BFF de verdade.**

Um proxy pode responder `200` ao canal e ter entregue ao BFF uma requisição
adulterada: com um header que o canal forjou, com a cadeia de encaminhamento
corrompida, com o corpo truncado. O status que o canal vê não diz nada sobre
isso.

O eco transforma "o que chegou do outro lado" em algo verificável. Quando o
teste 9 afirma que o header forjado não passou, a prova é a ausência dele no
JSON ecoado — não uma suposição.

E há um segundo uso, ainda mais importante: **a presença do eco prova que a
requisição chegou ao BFF; a ausência prova que não chegou.** Nos testes de
recusa, o que se confere é justamente isso — um sidecar que responde `401` e
encaminha assim mesmo é pior do que um que responde errado.

Como reconhecer de onde veio a resposta:

| A resposta tem | Veio de |
|----------------|---------|
| `path`, `headers`, `method` no corpo | **BFF** — a requisição atravessou |
| `error` e `correlationId`, corpo curto | **sidecar** — morreu ali |

### Aplicação

O par sidecar + BFF rodando junto, como num pod. É o que o `docker compose`
sobe.

---

### A topologia

```
   você (canal)                    ┌──── mesmo namespace de rede ────┐
        │                          │                                 │
        │  localhost:18080         │   sidecar          BFF (eco)    │
        └──────────────────────────┼──► :8080 ─────────► :8081       │
                                   │                                 │
                                   └─────────────────────────────────┘
                                              ↑
                              o BFF não expõe porta nenhuma:
                              só é alcançável pelo sidecar
```

No `docker-compose.yml`, a linha que garante isso:

```yaml
bff:
  network_mode: "service:sidecar"
```

Num pod do Kubernetes os containers compartilham o namespace de rede, e é isso
que faz `127.0.0.1:8081` funcionar entre eles. Sem essa linha, o BFF teria
endereço próprio e a validação seria de uma topologia que não é a de produção —
justamente a invariante mais importante do componente.

---

## Subindo a aplicação

```bash
mvn clean package -DskipTests
cd demo
docker compose down
docker compose up -d --build
```

O `clean` e o `--build` importam: sem eles o Docker reaproveita a camada
anterior e sobe com o jar antigo, e você acaba depurando um código que não está
rodando.

### Passo zero: conferir a matriz efetiva

```bash
docker compose logs sidecar | grep -A 8 "Matriz de interceptação"
```

Esperado:

```
Matriz de interceptação com 5 regra(s):
  pix-transfer [[POST] /api/v1/pix/transferencia]
  ted-transfer [[POST] /api/v1/transferencias/ted]
  boleto-payment [[POST] /api/v2/pagamentos/boletos/pagamento]
  pix-keys-register [[POST] /api/v1/pix/chaves]
  limites-consulta [[GET] /api/v1/conta/limites/transacionais]
Todo path fora da matriz é encaminhado ao BFF sem verificação.
```

**Conferir o número antes de qualquer outro teste.** Se não forem 5 regras, a
configuração não chegou ao container — e todos os testes seguintes vão enganar,
porque uma rota ausente da matriz responde `200` como se estivesse tudo certo.

A última linha do log sai em nível de alerta de propósito: ela lembra que a
matriz não é uma lista do que o sidecar recebe, e sim do que ele verifica. Todo
o resto atravessa.

---

## Os testes

No PowerShell use `curl.exe`, não `curl`: o apelido do PowerShell aponta para
outro comando, com sintaxe diferente.

---

### 1. A aplicação está de pé

**O que se verifica:** o sidecar subiu e o actuator responde na porta de
management, separada da porta que recebe o canal.

```bash
curl -s localhost:18090/actuator/health
```

**Esperado:**

```json
{"status":"UP","groups":["liveness","readiness"]}
```

**Se falhar:** o sidecar não subiu. `docker compose logs sidecar` mostra o
motivo — geralmente validação de configuração no boot.

---

### 2. O sidecar é invisível numa rota comum

**O que se verifica:** rota fora da matriz atravessa o sidecar e chega ao BFF
sem nenhuma verificação. O canal não percebe que existe um salto a mais.

```bash
curl -i localhost:18080/api/v1/conta/extrato
```

**Esperado:** `200`, com o eco do BFF:

```
HTTP/1.1 200
X-Correlation-Id: kQ8xR2vN7pL
x-powered-by: Express
Content-Type: application/json

{
  "path": "/api/v1/conta/extrato",
  "method": "GET",
  "headers": {
    "host": "127.0.0.1:8081",
    "x-forwarded-for": "172.18.0.1",
    "x-forwarded-host": "localhost:18080",
    ...
  }
}
```

**O que reparar:**

- `x-powered-by: Express` — a resposta veio do BFF, não do sidecar
- `"host": "127.0.0.1:8081"` — o sidecar reescreveu o `Host` para o BFF; o canal
  chamou `localhost:18080` e o BFF viu o loopback interno
- `X-Correlation-Id` — gerado pelo sidecar e devolvido ao canal

---

### 3. Query e corpo atravessam íntegros

**O que se verifica:** o sidecar não lê nem altera o conteúdo. Cada squad envia o
que precisa, e nada trava.

```bash
curl -i "localhost:18080/api/v1/conta/extrato?de=2026-01-01&ate=2026-01-31" \
  -H "Content-Type: application/json" \
  -d '{"descricao":"acentuação e ç"}'
```

**Esperado:** `200`, e no eco:

```json
{
  "query": { "de": "2026-01-01", "ate": "2026-01-31" },
  "body": { "descricao": "acentuação e ç" }
}
```

**O que reparar:** os dois parâmetros de query chegaram, e a acentuação está
intacta. O sidecar passou os bytes sem interpretar.

---

### 4. A matriz barra a operação sensível

**O que se verifica:** rota da matriz é barrada **antes** de chegar ao BFF.

```bash
curl -i -X POST localhost:18080/api/v1/pix/transferencia \
  -H "Content-Type: application/json" \
  -d '{"chave":"fulano@banco.com","valor":50}'
```

**Esperado:** `401`, com corpo curto do sidecar:

```
HTTP/1.1 401
X-Correlation-Id: kQ8xR2vN7pL
Content-Type: application/json

{"error":"confirmation_required","correlationId":"kQ8xR2vN7pL"}
```

**O que reparar — e este é o ponto do teste:**

- **Não há `path` no corpo** e **não há `x-powered-by: Express`**
- O corpo é curto, com `error` e `correlationId` — a assinatura de uma resposta
  escrita pelo sidecar

Compare com o teste 2: lá o corpo trazia o JSON ecoado, prova de que a
requisição chegou ao BFF. Aqui não há eco, e a ausência dele **é** a prova de
que a requisição morreu no sidecar.

Um proxy que respondesse `401` mas encaminhasse assim mesmo passaria por um
teste que só olhasse o status. É por isso que se confere o corpo, e não só o
código.

---

### 5. O verbo muda o desfecho no mesmo endereço

**O que se verifica:** a matriz é indexada por método **e** path. Listar as
próprias chaves é consulta; cadastrar uma nova redireciona dinheiro depois.

```bash
curl -s -o /dev/null -w "GET  chaves: %{http_code}\n"          localhost:18080/api/v1/pix/chaves
curl -s -o /dev/null -w "POST chaves: %{http_code}\n" -X POST  localhost:18080/api/v1/pix/chaves
```

**Esperado:**

```
GET  chaves: 200
POST chaves: 401
```

**O que reparar:** mesmo endereço, desfechos opostos. Se a matriz fosse indexada
só por path, seria preciso escolher entre proteger os dois — fricção na consulta
— ou nenhum — brecha no cadastro.

---

### 6. Interceptar não é sobre o verbo

**O que se verifica:** um `GET` também pode estar na matriz, quando a operação
justifica.

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:18080/api/v1/conta/limites/transacionais
```

**Esperado:** `401`

**O que reparar:** conhecer os limites transacionais de alguém é informação útil
para quem prepara uma fraude. O critério é o que a operação expõe, não o método
HTTP.

---

### 7. Variação de escrita não contorna a matriz

**O que se verifica:** as três formas abaixo chegam ao mesmo handler no BFF. Se
produzissem decisões diferentes, bastaria trocar a grafia para contornar a
proteção.

```bash
curl -s -o /dev/null -w "barra final:    %{http_code}\n" -X POST "localhost:18080/api/v1/pix/transferencia/"
curl -s -o /dev/null -w "barra dupla:    %{http_code}\n" -X POST "localhost:18080/api//v1/pix/transferencia"
curl -s -o /dev/null -w "percent-encode: %{http_code}\n" -X POST "localhost:18080/api/v1/%70ix/transferencia"
```

**Esperado:**

```
barra final:    401
barra dupla:    401
percent-encode: 401
```

**O que reparar:** `%70` é a letra `p`. O sidecar decodifica antes de comparar,
então `/api/v1/%70ix/...` é reconhecido como `/api/v1/pix/...`.

---

### 8. Path suspeito morre, não é corrigido

**O que se verifica:** navegação de diretório e separador codificado são
recusados com `400`, não resolvidos e encaminhados.

```bash
curl -s -o /dev/null -w "navegacao: %{http_code}\n" --path-as-is -X POST "localhost:18080/api/v1/pix/../pix/transferencia"
curl -s -o /dev/null -w "separador: %{http_code}\n"              -X POST "localhost:18080/api%2Fv1/pix/transferencia"
```

**Esperado:**

```
navegacao: 400
separador: 400
```

**O que reparar:** o `--path-as-is` impede o próprio curl de resolver o `..`
antes de enviar. Sem ele, o curl normaliza e o teste não testa nada.

Resolver o `..` no sidecar significaria decidir sobre um path e encaminhar
outro, dependendo de sidecar e BFF resolverem exatamente igual. Recusar elimina
a divergência.

---

### 9. O header forjado — o teste mais importante

**O que se verifica:** o canal tenta enviar o header que o sidecar reserva para
si, declarando por conta própria que a confirmação aconteceu. O sidecar
descarta.

```bash
curl -s localhost:18080/api/v1/conta/extrato -H "x-sidecar-verified: true"
```

**Esperado:** `200`, e dentro de `headers` no eco:

```json
"headers": {
  "host": "127.0.0.1:8081",
  "accept": "*/*",
  "user-agent": "curl/8.4.0",
  "x-forwarded-for": "172.18.0.1",
  "x-forwarded-host": "localhost:18080",
  "x-forwarded-proto": "http"
}
```

**O que reparar:** **`x-sidecar-verified` não está na lista.** O canal enviou, e
o BFF não recebeu.

Para conferir de forma direta:

```bash
curl -s localhost:18080/api/v1/conta/extrato -H "x-sidecar-verified: true" | grep -i sidecar-verified
```

**Esperado: nenhuma saída.**

Este é o teste que separa "o sidecar confia no canal" de "o sidecar não confia
no canal". Se o header atravessasse, qualquer um poderia declarar a confirmação
como feita.

---

### 10. A cadeia de encaminhamento chega correta

**O que se verifica:** o IP real do cliente é o dado usado em investigação de
fraude. Precisa chegar ao BFF completo e uma vez só.

```bash
curl -s localhost:18080/api/v1/conta/extrato -H "X-Forwarded-For: 203.0.113.9" | grep -i forwarded-for
```

**Esperado:** um único valor, com o IP recebido mais o salto do sidecar:

```json
"x-forwarded-for": "203.0.113.9, 172.18.0.1"
```

**O que reparar:** se aparecerem **dois** valores para o mesmo header, o sidecar
está duplicando — e o BFF leria um ou outro conforme a implementação. Foi um
defeito real numa versão anterior, encontrado por este teste.

---

### 11. Corpo acima do teto é recusado

**O que se verifica:** o teto de corpo protege a memória do pod. Sidecar e BFF
dividem o mesmo limite, e derrubar um derruba o outro.

```bash
head -c 2000 /dev/zero | tr '\0' 'x' > big.txt
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:18080/api/v1/conta/busca --data-binary "@big.txt"
```

No PowerShell:

```powershell
[System.IO.File]::WriteAllText("$PWD\big.txt", ("x" * 2000))
curl.exe -s -o NUL -w "%{http_code}`n" -X POST localhost:18080/api/v1/conta/busca --data-binary "@big.txt"
```

**Esperado:** `413`

**O que reparar:** o teto está em 1 KiB nesta validação, para o cenário ser fácil
de exercitar. Em produção, dimensionar pelo maior payload legítimo do canal.

---

### 12. Rastreabilidade ponta a ponta

**O que se verifica:** o identificador enviado pelo canal atravessa e aparece no
log — é o que liga um chamado de suporte a uma linha.

```bash
curl -i localhost:18080/api/v1/conta/extrato -H "X-Correlation-Id: chamado-4711"
```

**Esperado:** o mesmo valor de volta no header da resposta.

E no log do container:

```bash
docker compose logs sidecar | grep chamado-4711
```

**Esperado:** as linhas da requisição, todas com `[chamado-4711]` no prefixo.

**O que reparar:** um valor com caractere fora do formato permitido é
substituído, sem aviso — porque ele vai para o arquivo de log, e quebra de linha
ali permitiria forjar registros. Testar:

```bash
curl -is localhost:18080/api/v1/conta/extrato -H "X-Correlation-Id: abc.def" | grep -i correlation
```

**Esperado:** um valor gerado, diferente de `abc.def`.

---

## Resumo esperado

| # | Teste | Esperado |
|---|-------|----------|
| 1 | Saúde | `200` com `UP` |
| 2 | Rota comum | `200` com eco do BFF |
| 3 | Query e corpo | íntegros no eco |
| 4 | Rota da matriz | `401`, **sem** eco |
| 5 | GET vs POST no mesmo path | `200` e `401` |
| 6 | GET na matriz | `401` |
| 7 | Variação de escrita | `401` nas três |
| 8 | Path suspeito | `400` nas duas |
| 9 | Header forjado | não chega ao BFF |
| 10 | Cadeia de encaminhamento | um valor só |
| 11 | Corpo acima do teto | `413` |
| 12 | Correlação | mesmo valor no log |

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
| **`200` onde se esperava `401`** | **A rota não está na matriz — o caso mais perigoso.** Conferir o número de regras no log de boot |
| Porta em uso ao subir | Execução local do sidecar ainda rodando, ou container anterior não derrubado |
| O `..` não dá `400` | Faltou `--path-as-is`; o curl resolveu o path antes de enviar |