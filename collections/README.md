# Coleção do Postman — fluxo consultivo de ponta a ponta

Percorre o fluxo inteiro: o canal chama a rota protegida pelo sidecar, o
aplicativo resolve o desafio direto no provedor, e o polling conclui.

É o único fluxo exercitável assim — o de embarque de fator para no WebAuthn, que
exige a API do navegador.

---

## Importando

No Postman, **Import** e selecione os dois arquivos:

- `sidecar-fluxo-consultivo.postman_collection.json`
- `sidecar-homologacao.postman_environment.json`

Depois selecione o ambiente no canto superior direito.

## Preenchendo

| Variável | O que é |
|----------|---------|
| `sidecarUrl` | onde o sidecar está escutando |
| `rotaProtegida` | uma rota que esteja na matriz |
| `gatewayUrl` | endereço do provedor, terminando em `/am` |
| `realm` | `alpha` |
| `channelToken` | JWT do canal — **expira em cerca de uma hora** |
| `otpSecret` | semente do autenticador, em Base32 |

O `channelToken` e o `otpSecret` estão marcados como secretos: o Postman os
oculta e não os exporta junto da coleção.

---

## Executando

**Na ordem, uma a uma.** Cada requisição guarda o que a próxima precisa.

### 1. Canal chama a rota protegida — pelo sidecar

Esperado: `401 challenge_required`.

O script guarda o `authId` do PDC, extrai o identificador da transaction do
deeplink, e zera o contador do HOTP.

### 2. Aplicativo inicia a transaction — direto no provedor

**Não passa pelo sidecar.** É o papel do aplicativo, que em produção faria isso
pelo deeplink.

Esperado: `200` com um `authId` **diferente** e o callback de OTP.

### 3. Aplicativo submete o código — direto no provedor

O script de pré-requisição gera o código HOTP a partir da semente e o preenche
no callback recebido.

Esperado: `200` com `successUrl`.

**Se o código for recusado:** o contador local está atrasado em relação ao do
provedor. Rodar a requisição de novo avança um passo — repetir até casar.

### 4. Polling — pelo sidecar

Esperado: `200 authorized`.

Se vier `401 challenge_required`, o provedor ainda não registrou a conclusão.
Aguardar o `waitTime` e repetir.

---

## Os dois identificadores de jornada

O erro mais fácil de cometer:

| Variável | De onde vem | Onde é usado |
|----------|-------------|--------------|
| `authIdPdc` | requisição 1 | **polling** (requisição 4) |
| `authIdApp` | requisição 2 | **OTP** (requisição 3) |

São sessões independentes, e trocá-las faz a jornada não avançar sem dizer por
quê. A coleção os guarda em variáveis separadas justamente por isso.

---

## Sobre o contador do HOTP

O código é gerado por **contador**, não por tempo. Cada um vale uma vez, e o
contador precisa acompanhar o do provedor.

A coleção guarda o contador e o incrementa a cada geração. Se os dois
dessincronizarem — por um código gerado e não usado, por exemplo —, rodar a
requisição 3 de novo avança um passo.

Para reiniciar do zero: nas variáveis da coleção, `hotpCounter` para `0`.

---

## O que este fluxo valida

| | |
|---|---|
| A matriz decide corretamente | sim |
| O sidecar dispara a jornada certa | sim |
| Os callbacks atravessam sem alteração | sim |
| O polling funciona pelo endpoint do sidecar | sim |
| A obtenção do token acontece | sim, ao concluir |

É a validação de ponta a ponta que os cenários com gateway simulado não cobrem.
