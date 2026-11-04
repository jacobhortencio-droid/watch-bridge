# Watch Bridge

Liga o **Xiaomi Watch 2** (Wear OS) a um **Redmi K80 Ultra com ROM chinesa**, sem Mi Fitness e sem depender do módulo Wearable do Google Play Services.

- **WatchBridge-telemovel.apk** (Redmi): lê notificações, chamadas, música, agenda e meteorologia e envia-as ao relógio.
- **WatchBridge-relogio.apk** (relógio): mostra tudo com a interface nativa do Wear OS, mede a saúde e grava treinos.

Ligação principal: **Bluetooth de baixo consumo (BLE)** com um serviço próprio. Alternativa: **Wi-Fi** na mesma rede (porta 48211).
Tudo viaja **cifrado** (ECDH P-256 + AES-256-GCM) depois de um emparelhamento com código de 6 dígitos confirmado nos dois ecrãs.

## Funcionalidades

**Ligação e fiabilidade:** emparelhamento seguro, cifra e proteção contra repetição, compressão, modo acessório (Companion Device Manager + bonding), religação direta, verificação do arranque automático do HyperOS, configuração automática pelo Shizuku, registo partilhável, testes automáticos, atualização automática das duas apps.

**Notificações:** todas as apps com ícone, avatar e fotos; respostas rápidas personalizadas, voz e teclado; botões das notificações; apagar sincronizado; vibração por app e por contacto; contactos e palavras importantes; filtros por palavra; códigos de verificação em destaque; horário de silêncio; sincronização do Não incomodar; silêncio quando está a usar o telemóvel; histórico das últimas 50; ler em voz alta; notificações do relógio no telemóvel.

**Chamadas:** ecrã de chamada com nome e foto, atender, recusar, silenciar, recusar com SMS, desligar; recentes e favoritos; ligar a partir do relógio.

**Mostrador:** 3 mosaicos (principal, saúde, atalhos), 4 complicações (bateria do telemóvel, passos, frequência cardíaca, tempo), ícone de atividade em curso (música, navegação, treino).

**Rua:** navegação curva a curva do Google Maps (e Waze, Organic Maps, OsmAnd...), música com capa, progresso, volume pela coroa e troca de leitor, meteorologia (Open-Meteo), agenda, alarmes do telemóvel no pulso, encontrar telemóvel e relógio, alarme de separação, bateria dos dois aparelhos.

**Saúde:** frequência cardíaca a cada 15 minutos em segundo plano, passos, alertas de FC alta em repouso, lembretes para mexer e beber água, respiração guiada, treinos com GPS (corrida, caminhada, bicicleta) gravados em GPX, gráficos no telemóvel, sono estimado, exportação CSV/GPX.

**Ferramentas:** controlo do telemóvel (lanterna, som, Wi-Fi, dados, modo de avião, bloquear, captura), instalar APKs no relógio e gerir as apps do relógio a partir do telemóvel, automações (Tasker, MacroDroid, Home Assistant) e webhooks, cópia de segurança das definições.

## Instalar

Os APKs são compilados pelo GitHub Actions e publicados em **Releases → latest**.

### No Redmi
1. Instalar `WatchBridge-telemovel.apk`.
2. Na app: **Conceder permissões**, depois **⚡ Configurar tudo com o Shizuku** (ou os botões manuais), depois **Modo acessório**.
3. **Emparelhar relógio** e confirmar o mesmo código nos dois ecrãs.

### No relógio (primeira vez, por ADB sem fios)
1. Relógio: Opções de programador → Depuração ADB e Depuração por Wi-Fi.
2. No Redmi, com o **Wear Installer 2** ou o **Bugjaeger**, emparelhar e instalar `WatchBridge-relogio.apk`.
3. Abrir **Watch Bridge** no relógio e aceitar as permissões.
4. Depois disso, as atualizações fazem-se no relógio: **⬇ Atualizar app**.

### Opcional: notificações do relógio no telemóvel
Com o ADB ligado ao relógio, uma vez:
```
adb shell cmd notification allow_listener io.github.jacobhortencio.watchbridge.watch/io.github.jacobhortencio.watchbridge.watch.WatchNotifListener
```
e ativar em Definições → Notificações do relógio.

## Automações

Ativar em **Definições → Automações**.

| Sentido | Como |
|---|---|
| Enviar notificação ao relógio | Intent de difusão `io.github.jacobhortencio.watchbridge.NOTIFY` com extras `title` e `text` |
| Fazer o relógio tocar | Intent `io.github.jacobhortencio.watchbridge.FIND_WATCH` |
| Botões do relógio (mosaico Atalhos) | O telemóvel difunde `io.github.jacobhortencio.watchbridge.WATCH_BUTTON` com `button` (1 a 3) e `name`; e faz POST JSON ao webhook configurado |

## Limites conhecidos
- As análises fechadas da Xiaomi (sono detalhado, stress, SpO2) e as atualizações do sistema do relógio continuam a depender do Mi Fitness.
- O sono é uma estimativa pelos movimentos, não uma medição médica.
- O Android não deixa abrir apps em segundo plano: pedidos como "abrir app no relógio" aparecem como notificação para tocar.

## Notas técnicas
- A chave de assinatura é gerada no GitHub Actions e guardada em cache (nunca no repositório), para que as atualizações instalem por cima.
- A chave de emparelhamento nunca sai dos aparelhos, nem nas cópias de segurança.
