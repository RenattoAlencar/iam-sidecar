# Configuração do sidecar

O sidecar é um componente reutilizável: vários canais o consomem, cada um com
suas próprias rotas. Este documento explica cada campo, quem decide o valor e
como preenchê-lo.

---

## Quem configura o quê

| Bloco | Quem decide | Muda entre canais? |
|-------|-------------|--------------------|
| Portas | Plataforma | não |
| Alvo do encaminhamento | Plataforma | só a porta |
| Timeouts | Plataforma, com o canal | raramente |
| Teto de corpo | Canal | sim |
| Headers reservados | Time do sidecar | não |
| **Matriz de rotas** | **Canal propõe, segurança aprova** | **sim** |

A matriz é a única parte que muda de verdade a cada canal, e é a que exige
revisão. As demais são operacionais.

---

## Os campos

### `proxy.target` — onde está o BFF

```yaml
proxy:
  target: http://127.0.0.1:8081
```

Endereço do BFF **dentro do mesmo pod**. O canal nunca vê esse valor: ele
continua chamando o endereço de sempre, que agora aponta para o sidecar.

**Precisa ser loopback** (`127.0.0.1`, `localhost` ou `::1`). Isso é verificado
no boot, e o sidecar não sobe se apontar para outro lugar.

O motivo: o BFF só pode ser alcançável pelo sidecar. Se ele escutasse num
endereço de rede, qualquer aplicação do cluster falaria direto com ele e a
proteção deixaria de existir.

Normalmente só a porta muda entre canais.

---

### `proxy.connect-timeout` e `proxy.read-timeout` — prazos com o BFF

```yaml
proxy:
  connect-timeout: 2s
  read-timeout: 10s
```

- **connect-timeout** — tempo para estabelecer a conexão
- **read-timeout** — tempo para o BFF responder

Ambos obrigatórios. Sem limite, um BFF travado prende as threads do sidecar até
esgotá-las — e derruba junto o tráfego que nem passa por verificação.

O `read-timeout` precisa ser maior que o tempo da operação mais lenta do canal.
Se o BFF tem uma consulta que leva 8 segundos, um teto de 5 vai cortá-la.

---

### `proxy.max-body-bytes` — teto do corpo da requisição

```yaml
proxy:
  max-body-bytes: 2097152    # 2 MiB
```

Tamanho máximo de corpo que o sidecar aceita encaminhar. Acima disso, responde
`413` e a requisição não chega ao BFF.

**Dimensionar pelo maior payload legítimo do canal, com folga.** Sidecar e BFF
dividem o mesmo limite de memória do pod: um corpo grande demais derruba os dois.

Referência: uma foto em base64 — usada em confirmação por biometria — passa de
1 MB. Se o canal enviar imagens, medir o tamanho real antes de definir.

| Valor | Equivale a |
|-------|-----------|
| `1048576` | 1 MiB |
| `2097152` | 2 MiB |
| `5242880` | 5 MiB |

---

### `proxy.reserved-headers` — headers que só o sidecar escreve

```yaml
proxy:
  reserved-headers: ${SIDECAR_RESERVED_HEADERS:}
```

Lista de headers que o sidecar reserva para si. Eles são **descartados nos dois
sentidos**: o canal não consegue enviá-los ao BFF, e o BFF não consegue
devolvê-los ao canal.

**Aceita mais de um, separados por vírgula:**

```yaml
# values-hml.yaml
configmap:
  SIDECAR_RESERVED_HEADERS: x-sidecar-verified,x-sidecar-subject
```

Ou direto no arquivo, quando não vier de variável:

```yaml
proxy:
  reserved-headers:
    - x-sidecar-verified
    - x-sidecar-subject
```

> **Por que aqui a variável única funciona e na matriz não.** Nesta lista não há
> índice — ou a string inteira chega e vira a lista completa, ou não chega e a
> lista fica vazia. Na matriz, cada regra tem três campos e precisa de índice
> (`_0_`, `_1_`), e um erro no meio faz as regras seguintes desaparecerem sem
> que nada falhe.

A caixa não importa: `X-Sidecar-Verified` e `x-sidecar-verified` são o mesmo
header, e a comparação é feita numa forma canônica.

**Por que isso existe.** Quando o sidecar passar a injetar um header dizendo ao
BFF que a confirmação aconteceu, esse header precisa ser confiável. Se o canal
puder enviá-lo, ele declara a confirmação por conta própria e o BFF acredita —
sem que nada falhe, e sem deixar rastro.

**Quem mexe:** o time do sidecar, quando o componente passar a injetar um header
novo. O canal não precisa configurar nada aqui.

**Como conferir se está valendo**, no log de boot:

```
Headers reservados ao sidecar: [x-sidecar-verified]
```

Se aparecer `Nenhum header reservado ao sidecar configurado`, a lista não chegou.

---

### `proxy.intercept-rules` — a matriz

```yaml
proxy:
  intercept-rules:
    - name: pix-transfer
      path: /api/v1/pix/transferencia
      methods: [POST]
```

A lista de rotas que exigem confirmação do cliente. É a parte mais importante da
configuração.

| Campo | O que é |
|-------|---------|
| `name` | rótulo curto, aparece no log e na métrica. Não afeta o comportamento |
| `path` | o endereço da rota, como o canal a expõe |
| `methods` | quais verbos exigem confirmação nesse endereço |

**Três coisas que costumam gerar dúvida:**

**1. O método faz parte da chave.** O mesmo endereço com verbos diferentes são
entradas diferentes:

```yaml
    # cadastrar chave exige confirmação
    - name: pix-keys-register
      path: /api/v1/pix/chaves
      methods: [POST]

    # listar as próprias chaves não — e por isso não aparece aqui
```

**2. Ausência significa desprotegido, não ausente.** Toda requisição passa pelo
sidecar; a matriz decide o que ele **verifica**. Rota fora dela é repassada ao
BFF sem checagem — sem erro, sem alerta.

**3. O critério não é o verbo, é a operação.** Um `GET` pode exigir confirmação
se expuser algo sensível:

```yaml
    - name: limites-consulta
      path: /api/v1/conta/limites/transacionais
      methods: [GET]
```

**Critério sugerido para incluir uma rota:**

- move dinheiro
- cria ou altera credencial, chave ou dispositivo
- altera limite
- expõe dado que serve para se passar pelo cliente

**Quem decide:** o canal conhece os próprios endereços e propõe a lista; a
equipe de segurança classifica. A separação existe porque quem paga o custo da
proteção — fricção, latência, chamado de suporte — não deveria ser quem decide
o que proteger.

---

### Portas

```yaml
server:
  port: 8080          # recebe do canal

management:
  server:
    port: 8090        # actuator: health, métricas
```

O actuator fica em porta separada de propósito. Na porta principal ele seria
capturado pelo filtro e encaminhado ao BFF, e a sonda de prontidão passaria a
medir a saúde do BFF em vez da do sidecar.

A porta 8081 fica com o BFF, dentro do pod.

---

## Como configurar por ambiente

O `application.yml` que vai dentro do jar traz valores de desenvolvimento. Cada
canal sobrescreve o que precisa.

### Opção 1 — variáveis de ambiente (valores simples)

Funciona bem para tudo que não é lista:

```yaml
# values-hml.yaml
configmap:
  SIDECAR_TARGET: http://127.0.0.1:8081
  SIDECAR_PORT: "8080"
  SIDECAR_MANAGEMENT_PORT: "8090"
  SIDECAR_CONNECT_TIMEOUT: 2s
  SIDECAR_READ_TIMEOUT: 10s
  SIDECAR_MAX_BODY_BYTES: "2097152"

  # headers reservados: vários separados por vírgula, sem espaço
  SIDECAR_RESERVED_HEADERS: x-sidecar-verified

  # matriz: nome e endereço de cada rota
  SIDECAR_RULE_PIX_TRANSFER_NAME: pix-transfer
  SIDECAR_RULE_PIX_TRANSFER_PATH: /api/v1/pix/transferencia

  SIDECAR_RULE_PIX_KEYS_NAME: pix-keys-register
  SIDECAR_RULE_PIX_KEYS_PATH: /api/v1/pix/chaves

  SIDECAR_RULE_BOLETO_NAME: boleto-payment
  SIDECAR_RULE_BOLETO_PATH: /api/v2/pagamentos/boletos/pagamento

  SIDECAR_RULE_TED_NAME: ted-transfer
  SIDECAR_RULE_TED_PATH: /api/v1/transferencias/ted

  SIDECAR_RULE_LIMITES_NAME: limites-consulta
  SIDECAR_RULE_LIMITES_PATH: /api/v1/conta/limites/transacionais

  SIDECAR_RULE_CADASTRO_NAME: cadastro-consulta
  SIDECAR_RULE_CADASTRO_PATH: /api/v1/clientes/dados-cadastrais
```

**Cada rota tem sua própria variável, com nome próprio.** Isso é deliberado:
lista indexada por variável de ambiente (`_0_`, `_1_`) é frágil — o Spring lê
até não encontrar, e um erro no meio faz as regras seguintes **desaparecerem em
silêncio**. Uma rota sensível viraria passthrough sem que nada falhasse.

**O método fica no `application.yml`, não no values.** O endereço muda de canal
para canal; qual verbo é sensível não muda. Deixar o método na configuração de
ambiente permitiria desproteger uma rota editando um arquivo, sem revisão.

**Canal que não tem uma das rotas não precisa fazer nada** — a regra simplesmente
nunca casa.

### Opção 2 — arquivo montado (matriz completa)

Quando o canal precisa de uma matriz muito diferente da padrão, monta um arquivo
via ConfigMap:

```yaml
volumes:
  - name: sidecar-config
    configMap:
      name: sidecar-config

volumeMounts:
  - name: sidecar-config
    mountPath: /app/config

env:
  - name: SPRING_CONFIG_ADDITIONAL_LOCATION
    value: /app/config/
```

O arquivo sobrescreve o bloco inteiro, e um erro de sintaxe derruba o boot em
vez de truncar a lista.

---

## Conferindo se a configuração chegou

O sidecar imprime a configuração efetiva no boot. **Conferir isso antes de
considerar um ambiente pronto.**

```
Sidecar encaminhando para http://127.0.0.1:8081 (connectTimeout=PT2S, readTimeout=PT10S, corpo até 2097152 bytes)
Matriz de interceptação com 6 regra(s):
  pix-transfer [[POST] /api/v1/pix/transferencia]
  ted-transfer [[POST] /api/v1/transferencias/ted]
  boleto-payment [[POST] /api/v2/pagamentos/boletos/pagamento]
  pix-keys-register [[POST] /api/v1/pix/chaves]
  limites-consulta [[GET] /api/v1/conta/limites/transacionais]
  cadastro-consulta [[GET] /api/v1/clientes/dados-cadastrais]
Headers reservados ao sidecar: [x-sidecar-verified]
[WARN] Todo path fora da matriz é encaminhado ao BFF sem verificação.
```

**O que conferir:**

| Linha | Verificar |
|-------|-----------|
| `Sidecar encaminhando para` | o endereço é loopback |
| `Matriz com N regra(s)` | o número bate com o que foi configurado |
| cada regra | endereço e verbo corretos |
| `Headers reservados` | a lista aparece, se houver |

A última linha sai como alerta de propósito: ela lembra que tudo que não está
listado atravessa sem verificação.

---

## O que derruba o boot

O sidecar prefere não subir a subir com configuração inconsistente — um sidecar
que sobe errado aparenta estar protegendo.

| Situação | Mensagem |
|----------|----------|
| `target` não é loopback | *precisa apontar para loopback* |
| `target` sem host | *precisa conter host explícito* |
| Timeout zero ou negativo | *precisa ser positivo* |
| Matriz vazia | *deve declarar ao menos uma regra* |
| Regra sem `path` ou sem `methods` | *é obrigatório* |
| Duas regras com mesmo método e path | *entradas sobrepostas* |
| Padrão de path inválido | *padrão de path inválido na regra X* |