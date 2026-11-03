# Watch Bridge

Liga o **Xiaomi Watch 2** (Wear OS) a um **Redmi K80 Ultra com ROM chinesa**, sem Mi Fitness e sem depender do módulo Wearable do Google Play Services.

Duas apps comunicam diretamente:

- **WatchBridge-telemovel.apk** (Redmi): lê notificações e o leitor de música e envia-os ao relógio.
- **WatchBridge-relogio.apk** (relógio): mostra tudo com as notificações nativas do Wear OS.

Ligação principal: **Bluetooth de baixo consumo (BLE)** com um serviço próprio. Alternativa: **Wi-Fi** na mesma rede (porta 48211).

## Funcionalidades

| Função | Estado |
|---|---|
| Notificações de todas as apps, com ícone | ✅ |
| Responder a mensagens (WhatsApp, Telegram, SMS...) | ✅ via resposta rápida |
| Botões das notificações (marcar como lida, etc.) | ✅ |
| Apagar no relógio apaga no telemóvel, e vice-versa | ✅ |
| Chamadas: aviso + atender/recusar (botões do marcador) | ✅ |
| Controlo de música e volume | ✅ |
| Encontrar telemóvel / encontrar relógio | ✅ |
| Bateria do telemóvel no relógio | ✅ |
| Frequência cardíaca e passos enviados ao telemóvel | ✅ básico |

## Instalar

Os APKs são compilados automaticamente pelo GitHub Actions e publicados em **Releases → latest**.

### No Redmi
1. Descarregar `WatchBridge-telemovel.apk` e instalar.
2. Abrir a app e tocar, por ordem: **Conceder permissões**, **Ativar acesso às notificações**, **Bateria sem restrições**, **Arranque automático**.

### No relógio (só com o telemóvel, via Bugjaeger)
1. Relógio: Definições → Opções de programador → **Depuração ADB** e **Depuração por Wi-Fi** ativas. Relógio e Redmi na mesma rede Wi-Fi.
2. Redmi: instalar **Bugjaeger** (Play Store).
3. Relógio: tocar em **Pair new device** (aparece IP:porta e código de 6 dígitos).
4. Bugjaeger: menu de ligação por Wi-Fi → **Pair** → introduzir IP:porta e código.
5. Depois de emparelhar, ligar ao IP:porta principal (o que aparece em "IP address & Port").
6. Bugjaeger → secção de pacotes/APK → instalar `WatchBridge-relogio.apk`.
7. Abrir **Watch Bridge** no relógio e aceitar as permissões.

## Notas
- A chave de assinatura é gerada no GitHub Actions e guardada em cache (nunca no repositório), para que as atualizações instalem por cima.
- O HyperOS é agressivo a fechar apps: arranque automático e bateria sem restrições são obrigatórios no Redmi.
