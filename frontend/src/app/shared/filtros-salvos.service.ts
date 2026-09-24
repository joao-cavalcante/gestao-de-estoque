import { Injectable, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';

/**
 * Filtros de tela lembrados POR USUÁRIO (e tenant), neste navegador — o
 * operador filtra a fila, entra na conferência, volta, e os filtros continuam
 * lá (inclusive depois de recarregar a página). Busca por texto NÃO passa por
 * aqui de propósito: é pontual, não preferência.
 *
 * localStorage (não backend): preferência de tela, não dado de negócio; cada
 * estação/tablet guarda a sua. Falha de storage (modo privado, cota) só
 * significa "não lembra" — nunca quebra a tela.
 */
@Injectable({ providedIn: 'root' })
export class FiltrosSalvosService {
  private readonly auth = inject(AuthService);

  private chave(tela: string): string | null {
    const usuario = this.auth.usuario()?.id;
    const tenant = this.auth.obterTenantSlug();
    return usuario && tenant ? `wms_filtros:${tenant}:${usuario}:${tela}` : null;
  }

  /** Valor salvo da tela, ou null se não há (ou não dá pra ler). */
  ler<T>(tela: string): Partial<T> | null {
    const chave = this.chave(tela);
    if (!chave) return null;
    try {
      const bruto = localStorage.getItem(chave);
      return bruto ? (JSON.parse(bruto) as Partial<T>) : null;
    } catch {
      return null;
    }
  }

  salvar<T>(tela: string, valor: T): void {
    const chave = this.chave(tela);
    if (!chave) return;
    try {
      localStorage.setItem(chave, JSON.stringify(valor));
    } catch {
      /* storage indisponível — segue sem lembrar */
    }
  }
}
