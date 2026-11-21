#!/usr/bin/env bash
# Usuarios e schemas do Postgres por ambiente (T74): ctrade_stg dono so do schema staging, ctrade_soak dono so do
# schema soak. Nenhum dos dois tem permissao no schema do outro nem nos dados antigos do public: um TRUNCATE do soak
# nao alcanca o staging. Idempotente; ./up.sh chama a cada subida (tambem atualiza as senhas a partir do .env).
#
# Uso:  ./db-environments.sh
# Nunca imprime as senhas: o SQL vai pela entrada padrao do psql, nao pela linha de comando.
set -euo pipefail

cd "$(dirname "$0")"

env_value() { grep -E "^$1=" .env | cut -d= -f2-; }

for var in DB_PASSWORD_STG DB_PASSWORD_SOAK; do
  if ! [[ "$(env_value "$var")" =~ ^[A-Za-z0-9]{16,}$ ]]; then
    echo "$var no .env precisa ter 16 ou mais letras e numeros (openssl rand -hex 24)." >&2
    exit 1
  fi
done

if [[ -z "$(docker compose ps --status running -q postgres)" ]]; then
  echo "O Postgres nao esta rodando. Suba com: docker compose up -d postgres" >&2
  exit 1
fi

for _ in $(seq 30); do
  docker compose exec -T postgres pg_isready -U ctrade -d ctrade >/dev/null 2>&1 && break
  sleep 2
done

environment_sql() {
  local role=$1 schema=$2 password=$3
  cat <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$role') THEN
    CREATE ROLE $role LOGIN;
  END IF;
END \$\$;
ALTER ROLE $role WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '$password';
ALTER ROLE $role SET search_path = $schema;
CREATE SCHEMA IF NOT EXISTS $schema AUTHORIZATION $role;
ALTER SCHEMA $schema OWNER TO $role;
REVOKE ALL ON SCHEMA $schema FROM PUBLIC;
SQL
}

{
  environment_sql ctrade_stg staging "$(env_value DB_PASSWORD_STG)"
  environment_sql ctrade_soak soak "$(env_value DB_PASSWORD_SOAK)"
} | docker compose exec -T postgres psql -q -U ctrade -d ctrade -v ON_ERROR_STOP=1 >/dev/null

echo "Postgres: ctrade_stg -> schema staging, ctrade_soak -> schema soak."
