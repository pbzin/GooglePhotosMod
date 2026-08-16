# GooglePhotosMod

Módulo LSPosed para o Google Fotos. No álbum `Renegade Immortal`, o módulo abre o álbum automaticamente e exibe o nome real dos vídeos sobre cada item da lista, incluindo a extensão (`.mp4`, `.mkv`, etc.).

## Requisitos

- Android 8.0 ou superior.
- LSPosed instalado e ativo.
- Google Fotos com o pacote `com.google.android.apps.photos`.

## Instalação

1. Instale [`releases/GooglePhotosMod-debug.apk`](releases/GooglePhotosMod-debug.apk).
2. Ative o módulo no LSPosed para o Google Fotos.
3. Force o encerramento do Google Fotos ou reinicie o aparelho.

## Build

É necessário ter Gradle 8.6 ou superior, Android SDK com API 34 e acesso às dependências Maven:

```bash
gradle assembleDebug
```

O APK será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Observação

O Google Fotos usa classes e nomes obfuscados que podem mudar entre versões. Esta implementação foi validada com o Google Fotos `7.87.0.957333026`.
