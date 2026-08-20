# Sidecar de confirmação — funcionamento e integração

Documento para quem vai integrar ou operar o componente. Descreve o
comportamento, o consumo, as variáveis e o contrato com o canal.

Para os pontos em aberto e as decisões pendentes, ver `ARQUITETURA.md`.

---

## Como funciona

O sidecar roda no mesmo pod do BFF e recebe todo o tráfego do canal. Para cada
requisição, consulta a matriz de rotas configurada.

```
   canal ──► sidecar :8080 ──┬──► BFF :8081     rota fora da matriz
                             │
                             └──► gateway       rota na matriz
```

**Toda requisição passa pelo sidecar.** A matriz não decide o que chega até ele —
decide o que ele verifica.

| Situação | O que acontece |
|----------|----------------|
| Rota **fora** da matriz | encaminhada ao BFF sem verificação |
| Rota **na** matriz | jornada iniciada, desafio devolvido ao canal |
| Path malformado | recusado com `400`, não chega ao BFF |
| Enquadramento ambíguo | recusado com `400` |
| Corpo acima do teto | recusado com `413` |
| Gateway indisponível | `503`, e a requisição **não** é encaminhada |

O BFF escuta apenas em `127.0.0.1`. Não há caminho até ele que não passe pelo
sidecar.

---

## Como o canal chama e responde

### 1. Requisição normal, rota fora da matriz

Nada muda. O canal chama como sempre chamou.

```
POST /api/v1/conta/extrato
x-canal-autenticacao: <JWT>

→ 200, resposta do BFF
```

### 2. Requisição em rota da matriz

O canal chama normalmente. O sidecar barra e devolve o desafio.

```
POST /api/v1/pix/transferencia
x-canal-autenticacao: <JWT>
Content-Type: application/json

{ "chave": "...", "valor": 50 }
```

Resposta:

```
401
X-Correlation-Id: kQ8xR2vN7pL
Content-Type: application/json

{
  "status": "challenge_required",
  "authId": "eyJ0eXAiOiJKV1Qi...",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [
        { "name": "prompt", "value": "CHALLENGE_REQUIRED" },
        { "name": "defaultValue", "value": "BIOMETRIA:UNICO" }
      ],
      "input": [
        { "name": "IDToken1", "value": "BIOMETRIA:UNICO" }
      ]
    }
  ],
  "correlationId": "kQ8xR2vN7pL"
}
```

**A requisição de negócio não foi executada.** O canal precisa refazê-la depois
de concluir a jornada.

### 3. Respondendo aos passos da jornada

O canal preenche o campo `value` dentro de `input` e devolve **os callbacks
exatamente como recebeu** — mesma estrutura, mesma ordem, campos de `output`
inalterados.

```
POST /ciam/challenge
x-canal-autenticacao: <JWT>
Content-Type: application/json

{
  "authId": "<o mesmo do passo anterior>",
  "callbacks": [ ... com o value preenchido ... ]
}
```

Três respostas possíveis:

**Próximo passo** — repetir com os novos callbacks:

```
401 { "status": "challenge_required", "authId": "...", "callbacks": [...] }
```

**Espera** — o callback é do tipo de espera. Aguardar o tempo indicado em
`waitTime` e reenviar **o corpo inalterado**:

```
401 {
  "status": "challenge_required",
  "authId": "...",
  "callbacks": [
    { "type": "PollingWaitCallback",
      "output": [ { "name": "waitTime", "value": "5000" } ] }
  ]
}
```

**Conclusão:**

```
200 { "status": "authorized", "correlationId": "..." }
```

### 4. Refazendo a requisição de negócio

Depois de concluída a jornada, o canal reapresenta a requisição original.

> **Ponto em aberto.** O sidecar não guarda estado e hoje não tem como saber que
> a jornada aconteceu — ele dispararia outra. É o problema 2 do
> `ARQUITETURA.md`, e precisa de decisão antes de o fluxo fechar.

### Atalho: cliente com autenticador já configurado

Se o canal apresentar o código de um autenticador já configurado, o gateway pula
os passos de embarque:

```
POST /api/v1/pix/transferencia
x-canal-autenticacao: <JWT>
x-canal-codigo: 149707
```

Funciona apenas se o cabeçalho do código estiver configurado no sidecar.

---

## Códigos de resposta

| Código | `error` / `status` | O que significa | O que o canal faz |
|--------|--------------------|-----------------|-------------------|
| `200` | — | resposta do BFF | segue |
| `200` | `authorized` | jornada concluída | refaz a requisição |
| `400` | `bad_request` | path malformado ou enquadramento ambíguo | corrige a chamada |
| `401` | `challenge_required` | há desafio a responder | apresenta o desafio ao cliente |
| `401` | `session_required` | token do canal ausente | reautentica |
| `401` | `journey_expired` | sessão da jornada expirou | **reabre a jornada**, não é erro |
| `403` | `denied` | jornada negada pelo gateway | informa recusa ao cliente |
| `413` | `payload_too_large` | corpo acima do teto | reduz o payload |
| `502` | `bad_gateway` | BFF não respondeu | tenta novamente |
| `503` | `authorization_unavailable` | gateway de identidade fora | tenta novamente |

A distinção entre `403` e `401 journey_expired` importa: no primeiro o cliente
foi recusado; no segundo, apenas demorou.

---

## Rastreabilidade

Toda resposta carrega `X-Correlation-Id`. Se o canal enviar o cabeçalho, o mesmo
valor volta e aparece em todos os registros de log da requisição.

```
POST /api/v1/pix/transferencia
X-Correlation-Id: chamado-4711
```

No log do sidecar:

```
INFO [chamado-4711] Rota interceptada: regra=pix-transfer, método=POST
INFO [chamado-4711] Desafio emitido ao canal: regra=pix-transfer, callbacks=1
```

É o que liga um chamado de suporte a uma linha de log. Valor com caractere fora
de `[A-Za-z0-9_-]` é substituído sem aviso — o valor vai para o arquivo de log, e
quebra de linha ali permitiria forjar registros.

---

## Variáveis de ambiente

### Proxy

| Variável | Obrigatória | Padrão | O que é |
|----------|-------------|--------|---------|
| `SIDECAR_TARGET` | não | `http://127.0.0.1:8081` | endereço do BFF; **precisa ser loopback** |
| `SIDECAR_PORT` | não | `8080` | porta que recebe do canal |
| `SIDECAR_MANAGEMENT_PORT` | não | `8090` | porta do actuator |
| `SIDECAR_CONNECT_TIMEOUT` | não | `2s` | prazo para conectar ao BFF |
| `SIDECAR_READ_TIMEOUT` | não | `10s` | prazo para o BFF responder |
| `SIDECAR_MAX_BODY_BYTES` | não | `2097152` | teto do corpo, em bytes |
| `SIDECAR_RESERVED_HEADERS` | não | vazio | cabeçalhos que só o sidecar escreve, separados por vírgula |

### Matriz de rotas

Uma variável de nome e uma de path por rota. O método fica no `application.yml`.

| Variável | Exemplo |
|----------|---------|
| `SIDECAR_RULE_PIX_TRANSFER_NAME` | `pix-transfer` |
| `SIDECAR_RULE_PIX_TRANSFER_PATH` | `/api/v1/pix/transferencia` |
| `SIDECAR_RULE_PIX_KEYS_NAME` | `pix-keys-register` |
| `SIDECAR_RULE_PIX_KEYS_PATH` | `/api/v1/pix/chaves` |
| `SIDECAR_RULE_BOLETO_NAME` | `boleto-payment` |
| `SIDECAR_RULE_BOLETO_PATH` | `/api/v2/pagamentos/boletos/pagamento` |
| `SIDECAR_RULE_TED_NAME` | `ted-transfer` |
| `SIDECAR_RULE_TED_PATH` | `/api/v1/transferencias/ted` |
| `SIDECAR_RULE_LIMITES_NAME` | `limites-consulta` |
| `SIDECAR_RULE_LIMITES_PATH` | `/api/v1/conta/limites/transacionais` |
| `SIDECAR_RULE_CADASTRO_NAME` | `cadastro-consulta` |
| `SIDECAR_RULE_CADASTRO_PATH` | `/api/v1/clientes/dados-cadastrais` |

Canal que não tem uma dessas rotas não precisa fazer nada — a regra nunca casa.

**Não se usa índice de lista em variável de ambiente.** O Spring lê a lista por
índice até não encontrar, e um erro no meio faz as regras seguintes desaparecerem
em silêncio — uma rota sensível viraria passthrough sem que nada falhasse.

### Gateway de identidade

| Variável | Obrigatória | Padrão | O que é |
|----------|-------------|--------|---------|
| `IDENTITY_BASE_URL` | **sim** | — | endereço do gateway, terminando no caminho base; **HTTPS** |
| `IDENTITY_REALM` | não | `alpha` | realm do provedor |
| `IDENTITY_JOURNEY` | **sim** | — | nome da jornada; sem padrão, porque a errada autentica por caminho não pretendido |
| `IDENTITY_JOURNEY_TYPE` | não | `service` | tipo do índice de autenticação |
| `IDENTITY_CLIENT_ID` | **sim** | — | cliente OAuth do sidecar |
| `IDENTITY_CLIENT_SECRET` | **sim** | — | segredo do cliente; **vem de Secret, nunca de ConfigMap** |
| `IDENTITY_REDIRECT_URI` | **sim** | — | endereço de retorno registrado no cliente OAuth |
| `IDENTITY_SCOPES` | não | `openid` | escopos pedidos, separados por espaço |
| `IDENTITY_SESSION_COOKIE_NAME` | **sim** | — | nome do cookie de sessão; **específico da instalação** |
| `IDENTITY_CHANNEL_TOKEN_HEADER` | **sim** | — | cabeçalho pelo qual o canal apresenta o token |
| `IDENTITY_AUTHENTICATOR_CODE_HEADER` | não | vazio | cabeçalho do código do autenticador; vazio desativa o atalho |
| `IDENTITY_CONNECT_TIMEOUT` | não | `2s` | prazo para conectar ao gateway |
| `IDENTITY_READ_TIMEOUT` | não | `10s` | prazo para o gateway responder |

### Contrato com o canal

| Variável | Obrigatória | Padrão | O que é |
|----------|-------------|--------|---------|
| `CHANNEL_CHALLENGE_PATH` | não | `/ciam/challenge` | path onde o canal responde aos passos |

**Nunca pode estar coberto pela matriz** — o boot recusa a configuração, porque
exigir confirmação para responder ao desafio prenderia o cliente num ciclo.

### Log

| Variável | Padrão | O que é |
|----------|--------|---------|
| `LOG_LEVEL` | `INFO` | nível geral |
| `LOG_LEVEL_SIDECAR` | `INFO` | nível do componente; `DEBUG` para diagnóstico |

---

## Consumo e dimensionamento

### Onde o sidecar entra no caminho

| Item | Custo |
|------|-------|
| Salto até o BFF | loopback, sem rede física |
| Cópia de cabeçalhos | proporcional à quantidade |
| Corpo | passa em fluxo, sem acumular em memória |
| Decisão de rota | casamento de padrão já compilado no boot |

O impacto em latência é pequeno para tráfego que apenas atravessa.

### Memória

**Sidecar e BFF dividem o limite do pod.** O limite precisa acomodar os dois, e
um corpo grande demais derruba ambos — é por isso que existe o teto de corpo.

Dimensionar o `SIDECAR_MAX_BODY_BYTES` pelo maior payload legítimo do canal, com
folga. Se o canal enviar captura biométrica, medir o tamanho real: o conteúdo vai
dentro de um JSON em texto, dentro de outro JSON, e o escape infla bastante.

### O que ainda não foi medido

**Latência sob carga.** Não houve teste de carga. O custo por requisição é
pequeno em teoria; o comportamento com o pool de threads saturado não foi
observado.

**O polling da biometria.** A jornada tem espera com repetição a cada poucos
segundos. Uma análise de 30 segundos são cerca de 6 chamadas por cliente, cada
uma passando pelo sidecar e indo ao gateway. Com volume, isso muda o
dimensionamento — e não sabemos por quanto.

---

## Verificando a instalação

O sidecar imprime a configuração efetiva no boot. **Conferir antes de considerar
um ambiente pronto.**

```
Sidecar encaminhando para http://127.0.0.1:8081 (connectTimeout=PT2S, readTimeout=PT10S, corpo até 2097152 bytes)
Matriz de interceptação com 6 regra(s):
  pix-transfer [[POST] /api/v1/pix/transferencia]
  ...
Headers reservados ao sidecar: [x-sidecar-verified]
Endpoint de resposta ao desafio: /ciam/challenge
Gateway de identidade: IdentityProperties[baseUrl=..., journey=factor-onboarding, clientSecret=***, ...]
[WARN] Todo path fora da matriz é encaminhado ao BFF sem verificação.
```

**O que conferir:**

| Linha | Verificar |
|-------|-----------|
| `Sidecar encaminhando para` | o endereço é loopback |
| `Matriz com N regra(s)` | o número bate com o configurado |
| cada regra | endereço e verbo corretos |
| `Gateway de identidade` | a jornada é a esperada |

O último aviso é deliberado: tudo que não está listado atravessa sem verificação.

---

## O que derruba o boot

O sidecar prefere não subir a subir com configuração inconsistente — um sidecar
que sobe errado aparenta estar protegendo.

| Situação | Por quê |
|----------|---------|
| `SIDECAR_TARGET` não é loopback | BFF alcançável de fora anula o componente |
| Timeout zero ou negativo | é lido como "sem limite" pela pilha HTTP |
| Matriz vazia | aparentaria proteger sem proteger nada |
| Regra sem path ou sem método | regra incompleta não casa nada |
| Duas regras com mesmo método e path | qual vale dependeria da ordem do arquivo |
| Padrão de path inválido | falharia na primeira requisição da rota |
| Matriz cobrindo o endpoint do desafio | prende o cliente num ciclo sem saída |
| `IDENTITY_JOURNEY` ausente | não há padrão seguro |
| `IDENTITY_BASE_URL` em HTTP claro | por ali trafegam credenciais |
| Cliente OAuth incompleto | falharia só ao emitir o token |