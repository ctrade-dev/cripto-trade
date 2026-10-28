# Validacao

## Regra geral

- Executar a validacao mais restrita possivel para os modulos tocados
- Se a mudanca atravessar fronteiras, compilar todos os modulos afetados
- Se houver comportamento arriscado, preferir teste alem de compile

## Comandos oficiais

Usar o wrapper do repositorio para manter o cache local consistente:

```powershell
./scripts/gradle-run.ps1 -q :core:compileJava
./scripts/gradle-run.ps1 -q :spring-application:compileJava
./scripts/gradle-run.ps1 -q :adapter-mock:compileJava
./scripts/gradle-run.ps1 -q :adapter-binance:compileJava
./scripts/gradle-run.ps1 -q :adapter-coinbase:compileJava
```

## Escopo por tipo de mudanca

- Mudanca so no `core`: compilar `:core`
- Mudanca de wiring no `spring-application`: compilar `:spring-application`
- Mudanca de adapter: compilar o adapter tocado e os modulos diretamente afetados
- Mudanca em contrato compartilhado: compilar todos os consumidores

## Expectativa minima em reviews

- Explicitar quando so foi feito compile
- Explicitar quando nao foi possivel testar
- Explicitar quando faltam testes para um comportamento arriscado

