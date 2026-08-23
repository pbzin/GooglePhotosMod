<img src="./app/src/main/ic_launcher-playstore.png" width="96" height="96" alt="GooglePhotosMod icon" />

<p align="left">
  <a href="https://visitorbadge.io/status?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FGooglePhotosMod">
    <img src="https://api.visitorbadge.io/api/visitors?path=https%3A%2F%2Fgithub.com%2Fpbzin%2FGooglePhotosMod&label=repo%20views&countColor=%230e75b6&style=flat" alt="Repo Views" />
  </a>
  &nbsp;
  <a href="https://github.com/pbzin/GooglePhotosMod/releases">
    <img src="https://img.shields.io/github/downloads/pbzin/GooglePhotosMod/total?style=flat&color=0e75b6&label=downloads" alt="Downloads" />
  </a>
</p>

# GooglePhotosMod

Mods e aprimoramentos para o aplicativo Google Fotos via LSPosed. Este módulo adiciona funcionalidades úteis e correções técnicas para melhorar a experiência de gerenciamento de mídia.

## Funcionalidades (Hooks)

### 🎥 Exibição de Nome Real de Arquivo
Exibe o nome original do arquivo de vídeo (ex: `video_01.mp4`, `ferias.mkv`) diretamente na grade (grid) principal do Google Fotos. 
*   **Como funciona**: Faz o hook no `PhotoCellView.draw` e resolve dinamicamente o título do objeto de mídia, permitindo identificar arquivos rapidamente sem abrir os detalhes.

### ⚙️ Forçar Decoder HEVC via Software
Opção para contornar falhas de reprodução em vídeos de altíssima resolução (ex: 4K+ com proporções específicas) que o decodificador de hardware (Qualcomm) pode rejeitar.
*   **Como funciona**: Intercepta a criação do `MediaCodec` e substitui o decodificador padrão pelo `c2.android.hevc.decoder` do sistema.

### ⏳ Otimização de Backup (Smart Hold)
Evita que o sistema encerre prematuramente as tarefas de backup do Google Fotos enquanto ainda há tráfego de dados ativo.
*   **Como funciona**: Monitora o tráfego de rede do UID do aplicativo e atrasa a chamada de `jobFinished` em serviços de backup específicos se o upload ainda estiver em andamento.

## Requisitos

*   Android 8.0 (Oreo) ou superior.
*   Ambiente **LSPosed** configurado e ativo.
*   Google Fotos instalado (`com.google.android.apps.photos`).

## Instalação

1.  Baixe o APK mais recente na aba [Releases](https://github.com/pbzin/GooglePhotosMod/releases).
2.  Instale o módulo e ative-o no gerenciador do LSPosed, selecionando o Google Fotos como escopo.
3.  Reinicie o Google Fotos (Force Stop) para que as alterações entrem em vigor.
4.  Acesse as configurações do módulo para ativar/desativar o decoder HEVC via software ou o Smart Hold de backup.

## Desenvolvimento e Build

O projeto utiliza **Android Gradle Plugin 9.3.1** com suporte nativo a Kotlin e **Gradle 9.5.0**.

```bash
./gradlew app:assembleDebug
```

O APK final será gerado em: `app/build/outputs/apk/debug/app-debug.apk`.

---
*Nota: Este projeto é um módulo Xposed e não possui afiliação oficial com o Google.*
