# Rastreador veicular — cópia de trabalho

Esta cópia preserva o ZIP recebido em `fonte-original-rastreador.zip` e acrescenta o app Android com login, sincronização da última posição, armazenamento local da posição mais recente sem internet, notificação persistente, painel web e regras do Firestore publicadas. O código está em um repositório público; o painel ainda não foi implantado e nenhum outro projeto Firebase foi alterado.

## Situação atual e escolhas

- Projeto dedicado: `rastreador-arames-brasil`, conta proprietária confirmada `aramesbrasil40@gmail.com`.
- O Cloud Firestore foi criado no plano Spark, região São Paulo (`southamerica-east1`). A região não pode ser trocada depois da criação.
- O provedor Firebase Authentication de e-mail/senha foi ativado. O cadastro é restrito na interface ao e-mail autorizado; a verificação do e-mail é exigida pelo app, painel e regras.
- As regras restritivas do Firestore (`firebase/firestore.rules`) foram publicadas no projeto; o Console confirmou sucesso nesta sessão. A regra limita leitura/gravação ao e-mail verificado `aramesbrasil40@gmail.com` e ao documento `vehicles/vehicle-1`, com listagem e exclusão negadas. Authentication por e-mail/senha está habilitado; nenhuma conta foi cadastrada.
- Os apps Android e Web estão registrados no Firebase. A configuração Web foi obtida e salva somente na cópia local. O `google-services.json` do Android ainda não foi instalado: o botão de download do Console foi acionado, mas o arquivo não ficou disponível para esta cópia; a API oficial `projects.androidApps.getConfig` respondeu HTTP 401 por falta de credencial OAuth nessa ferramenta. Não foi fabricado nem adicionado um JSON substituto. Nenhum segredo ou chave privada foi colocado no código.
- A compilação não foi confirmada: esta máquina não tem Java/JDK, Gradle, Android SDK nem `adb` instalados. Uma tentativa de baixar o JDK 17 portátil foi interrompida pela cota de tamanho do workspace; os arquivos temporários foram removidos. Não houve teste em aparelho.

## Fluxo de dados / segurança

- Só uma localização atual é mantida na nuvem: `vehicles/vehicle-1`; não há trilha histórica nem envio de rotas passadas.
- O app grava até uma vez por minuto ou quando se move pelo menos 100 m. O intervalo de GPS (15 s / 10 m) é uma solicitação ao Android, não uma garantia.
- Sem internet validada, guarda no armazenamento privado do app somente a última coordenada e tenta enviá-la ao recuperar conexão; posições intermediárias offline não são enfileiradas. O painel marca dados com mais de 5 minutos como possivelmente desatualizados e diferencia cache do navegador.
- A leitura e escrita devem ficar limitadas a usuários autenticados com e-mail verificado exatamente `aramesbrasil40@gmail.com`; listagem e exclusão são negadas. O Firestore deve conter somente o documento `vehicles/vehicle-1`.
- A configuração Firebase de cliente (API key/app ID) é pública por design; proteção vem de Authentication + regras. **Nunca** embuta chave de service account, senha, token privado ou arquivo JSON de conta de serviço.

## 1. Finalizar a configuração do Firebase

No Console do projeto `rastreador-arames-brasil`, usando a conta `aramesbrasil40@gmail.com`:

1. Em Authentication, confirme que o provedor E-mail/senha está ativado e ative a confirmação de e-mail, se ainda não estiver.
2. As regras de `firebase/firestore.rules` foram publicadas e o Console confirmou sucesso nesta sessão. Confira no Console que a versão ativa continua restrita ao e-mail permitido e não contém `allow read, write: if true`; não abra o banco para todos.
3. Em Configurações do projeto > Seus apps, use o app Android já registrado com pacote `br.com.rastreadorveicular` (ID `1:28890842793:android:09c8cbd2825b13b33c0bb8`). Baixe o `google-services.json` oficial para `android/RastreadorVeicular/app/google-services.json`. Ele não foi obtido nesta sessão; não use JSON de conta de serviço.
4. No checkout do repositório, copie `web/firebase-config.example.js` para `web/firebase-config.js` e preencha com a configuração do app Web já registrado, mostrada pelo Console. Uma cópia local já foi preenchida, mas não está no repositório. Essa configuração é pública, mas específica deste projeto.
5. Crie a conta no próprio app Android com o e-mail exato autorizado e uma senha forte que você guarda num gerenciador de senhas; abra o link de verificação enviado ao e-mail. Não coloque a senha no projeto, no APK, no painel ou no chat. Se a conta já existir, use Entrar.
6. Faça o primeiro lançamento de posição com o app, confirme no Console que surgiu somente `vehicles/vehicle-1` e teste o painel autenticado. Não compartilhe o acesso do painel.

## 2. Compilar o Android

1. Instale Android Studio atual com JDK 17 e Android SDK 35.
2. Abra `android/RastreadorVeicular` no Android Studio e sincronize o Gradle (requer conexão para baixar dependências).
3. Confirme o `google-services.json` no caminho indicado acima; ele não deve ser substituído pelo JSON de uma conta de serviço.
4. Execute em um aparelho Android com Google Play Services. Faça login e verifique o e-mail; conceda localização precisa e notificações. Inicie manualmente pelo botão; use o botão Parar na tela ou na notificação para parar.
5. Android e fabricantes podem limitar o rastreamento em segundo plano. Em Configurações do app permita localização conforme as telas do aparelho e, se necessário, permita atividade em segundo plano/remova otimização de bateria. O serviço começa apenas quando iniciado com o app visível.

## 3. Painel privado na internet (opcional, ainda não publicado)

1. Prepare `web/firebase-config.js` conforme o passo 4.
2. Para visualizar localmente, sirva os arquivos HTTP (módulos ES não funcionam de forma confiável abrindo `file://`):
   `python3 -m http.server 8080 --directory web`
3. Para hospedar mais tarde, instale Firebase CLI, entre com a conta correta e execute na raiz desta pasta: `firebase deploy --only hosting`.
4. A hospedagem de conteúdo estático é pública; as coordenadas não estão embutidas no site e continuam bloqueadas pelas regras até login. O painel usa autenticação Firebase e uma assinatura em tempo real no único documento autorizado. O mapa utiliza Leaflet e tiles do OpenStreetMap.
5. O deploy foi deixado para depois da autorização explícita do proprietário; não foi feito.

## Custos e limites

O projeto está no Spark, sem configurar faturamento. Firestore e Firebase Hosting têm cotas gratuitas sujeitas aos limites atuais do Google; ultrapassar cotas pode suspender leituras/escritas ou hospedagem, e recursos como tráfego adicional/serviços pagos podem exigir upgrade. O app reduz custo enviando apenas a posição atual com limite por tempo/distância. Monitorar as cotas e o uso é necessário. O projeto não usa serviço de rastreamento pago de terceiros.

## Testes realizados / pendentes

- Inspeção do ZIP de origem e preservação dele na cópia: concluído.
- Verificação manual dos arquivos de código/configuração: feita.
- Regras restritivas do Firestore: publicadas no Console; sucesso confirmado nesta sessão.
- `google-services.json` Android: não obtido; o endpoint oficial exigiu OAuth e o download pelo Console não disponibilizou o arquivo nesta cópia. Validação da integração Android pendente.
- Validação estática deste checkout: XML do Manifest e integridade do ZIP original conferidos; compilação Android não realizada por ausência de JDK/Gradle/SDK e pelo JSON ausente.
- Autorização Firebase ponta a ponta, queda/retorno de internet, localização real, tela apagada, movimento e teste no veículo: não realizados.
