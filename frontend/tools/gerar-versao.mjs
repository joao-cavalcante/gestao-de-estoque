// Gera src/app/shared/app-header/versao.gerada.ts no build de produção (Dockerfile.prod).
// Versão exibida no cabeçalho: <major>.<minor> do package.json + nº do build (quantidade de
// commits, APP_BUILD) — ex.: v4.2.187. APP_COMMIT e a data do build vão no tooltip.
// Sem os build args (deploy antigo), sai só a data do build, nunca um número inventado.
import { readFileSync, writeFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
const [major = '0', minor = '0'] = String(pkg.version ?? '0.0.0').split('.');
const build = (process.env.APP_BUILD ?? '').trim();
const commit = (process.env.APP_COMMIT ?? '').trim();

const versao = {
  numero: build ? `${major}.${minor}.${build}` : `${major}.${minor}`,
  commit: commit || null,
  buildEm: new Date().toISOString(),
};

const destino = new URL('../src/app/shared/app-header/versao.gerada.ts', import.meta.url);
writeFileSync(
  destino,
  `// GERADO por tools/gerar-versao.mjs no build de produção — não editar à mão.\n` +
    `export const VERSAO: { numero: string; commit: string | null; buildEm: string | null } = ${JSON.stringify(versao, null, 2)};\n`,
);
console.log('versão do front:', versao);
