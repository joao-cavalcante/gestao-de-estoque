import { Injectable, inject } from '@angular/core';
import { AuthService } from '../../auth/auth.service';

/**
 * Logo do cliente pro crachá, em PRETO E BRANCO puro (limiar, sem cinza) e como
 * data URL — o SVG embute a imagem, então a impressão e o PDF (svg2pdf) usam o
 * mesmo arquivo sem nova requisição. Mesma origem das etiquetas:
 * assets/logos/<slug>.png, com fallback .jpg. Sem logo → null (crachá sai sem).
 */
@Injectable({ providedIn: 'root' })
export class CrachaLogoService {
  private readonly auth = inject(AuthService);
  private cache: Promise<string | null> | null = null;

  obter(): Promise<string | null> {
    if (!this.cache) this.cache = this.carregar();
    return this.cache;
  }

  private async carregar(): Promise<string | null> {
    const slug = this.auth.obterTenantSlug();
    if (!slug) return null;
    for (const ext of ['png', 'jpg']) {
      const img = await this.carregarImagem(`/assets/logos/${slug}.${ext}`);
      if (img) return this.monocromatico(img);
    }
    return null;
  }

  private carregarImagem(src: string): Promise<HTMLImageElement | null> {
    return new Promise((resolve) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = () => resolve(null);
      img.src = src;
    });
  }

  /** Luminância < 170 vira preto, o resto branco; transparência preservada. */
  private monocromatico(img: HTMLImageElement): string | null {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      ctx.drawImage(img, 0, 0);
      const dados = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const px = dados.data;
      for (let i = 0; i < px.length; i += 4) {
        const lum = 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];
        const v = lum < 170 ? 0 : 255;
        px[i] = px[i + 1] = px[i + 2] = v;
      }
      ctx.putImageData(dados, 0, 0);
      return canvas.toDataURL('image/png');
    } catch {
      return null;
    }
  }
}
