import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { EntradaService, Recebimento } from '../../entrada/entrada.service';
import { qtdPalete } from '../../paletes/paletes.service';
import { OqSpinnerComponent } from '../../shared/icons/oq-spinner.component';
import { Doca, RecebimentoDocaService } from './recebimento-doca.service';

/**
 * Comprovante do motorista (27/11/2026, D1): o que foi recebido na doca — volumes da nota × paletes, avarias, checklist
 * da chegada — para o motorista assinar (ou fotografar). Térmica de 100 mm, altura livre, window.print().
 */
@Component({
  selector: 'app-comprovante-recebimento',
  standalone: true,
  imports: [OqSpinnerComponent, DatePipe, DecimalPipe],
  template: `
    @if (doca(); as d) {
      <div class="cp-toolbar"><button type="button" (click)="imprimir()">Imprimir</button></div>
      <article class="cp">
        <h1>Comprovante de recebimento</h1>
        <p class="cp-quando">{{ agora | date: 'dd/MM/yyyy HH:mm' }}</p>
        <dl>
          <dt>Nº único</dt><dd>{{ d.nunota }}</dd>
          @if (rec()?.numeroNota) { <dt>NF</dt><dd>{{ rec()?.numeroNota }}</dd> }
          <dt>Fornecedor</dt><dd>{{ rec()?.fornecedor ?? '—' }}</dd>
          <dt>Placa</dt><dd>{{ d.chegada?.placa ?? d.nota.placa ?? '—' }}</dd>
          @if (d.chegada) { <dt>Chegada</dt><dd>{{ d.chegada.chegadaEm | date: 'HH:mm' }}</dd> }
        </dl>
        <h2>Volumes</h2>
        <dl>
          <dt>Pela nota</dt><dd>{{ d.nota.volumes ?? '—' }} {{ d.nota.especie ?? '' }}</dd>
          <dt>Recebidos</dt><dd>{{ d.volumesFechados }} em {{ d.paletes.length }} paletes</dd>
          @if (d.nota.pesoBruto != null) { <dt>Peso bruto NF</dt><dd>{{ d.nota.pesoBruto | number: '1.0-1' }} kg</dd> }
        </dl>
        @if (diferenca(); as dif) { <p class="cp-destaque">Diferença de volumes: {{ dif }}</p> }
        @if (avarias().length) {
          <h2>Avarias</h2>
          <ul>
            @for (a of avarias(); track $index) { <li>{{ a }}</li> }
          </ul>
        }
        @if (d.chegada?.lacre || d.chegada?.bau || d.chegada?.temperaturaBau != null) {
          <h2>Inspeção do veículo</h2>
          <dl>
            @if (d.chegada?.lacre) { <dt>Lacre</dt><dd>{{ d.chegada?.lacre }}</dd> }
            @if (d.chegada?.bau) { <dt>Baú</dt><dd>{{ d.chegada?.bau }}</dd> }
            @if (d.chegada?.temperaturaBau != null) { <dt>Temperatura</dt><dd>{{ d.chegada?.temperaturaBau }} °C</dd> }
          </dl>
        }
        <p class="cp-nota">Quantidades conferidas na doca. Divergências de produto são tratadas com o fornecedor pela nota.</p>
        <div class="cp-assina"><span></span>Motorista (nome e documento)</div>
        <div class="cp-assina"><span></span>Conferente</div>
      </article>
    } @else {
      <p class="cp-msg"><oq-spinner [size]="13" /> Montando o comprovante…</p>
    }
  `,
  styles: `
    :host { display: block; background: #fff; color: #000; font-family: Arial, sans-serif; }
    .cp-msg { padding: 24px; display: flex; gap: 8px; align-items: center; }
    .cp-toolbar { padding: 12px 16px; }
    .cp-toolbar button { padding: 8px 14px; border: 1px solid #1f6feb; background: #1f6feb; color: #fff; border-radius: 6px; cursor: pointer; }
    .cp { width: 100mm; box-sizing: border-box; padding: 3mm; margin: 0 auto; font-size: 10pt; }
    h1 { font-size: 13pt; margin: 0; }
    h2 { font-size: 10.5pt; margin: 3mm 0 1mm; border-top: 0.3mm solid #000; padding-top: 1.5mm; }
    .cp-quando { margin: 0 0 2mm; }
    dl { display: grid; grid-template-columns: auto 1fr; gap: 0.6mm 3mm; margin: 0; }
    dt { font-weight: 700; } dd { margin: 0; }
    ul { margin: 0; padding-left: 4mm; }
    .cp-destaque { font-weight: 800; border: 0.4mm solid #000; padding: 1.5mm; margin: 2mm 0; }
    .cp-nota { font-size: 8pt; margin: 3mm 0; }
    .cp-assina { margin-top: 9mm; font-size: 9pt; }
    .cp-assina span { display: block; border-top: 0.3mm solid #000; margin-bottom: 1mm; }
    @media print { .cp-toolbar { display: none; } @page { size: 100mm auto; margin: 0; } }
  `,
})
export class ComprovanteRecebimentoComponent implements OnInit {
  private readonly api = inject(RecebimentoDocaService);
  private readonly entrada = inject(EntradaService);
  private readonly nunota = Number(inject(ActivatedRoute).snapshot.paramMap.get('nunota'));

  readonly doca = signal<Doca | null>(null);
  readonly rec = signal<Recebimento | null>(null);
  readonly agora = new Date();

  readonly diferenca = computed(() => {
    const d = this.doca();
    if (!d?.nota.volumes) return null;
    const dif = Math.round((d.volumesFechados - d.nota.volumes) * 1000) / 1000;
    return dif === 0 ? null : dif > 0 ? `+${dif} (a mais)` : `${dif} (a menos)`;
  });

  readonly avarias = computed(() =>
    (this.doca()?.paletes ?? []).flatMap((p) =>
      p.itens.filter((i) => i.qtdAvaria).map((i) => {
        const q = i.qtdComercial && i.qtd ? (i.qtdAvaria ?? 0) / (i.qtd / i.qtdComercial) : i.qtdAvaria;
        return `${p.uma} · ${i.codprod} ${i.descricao ?? ''} · ${Math.round((q ?? 0) * 1000) / 1000} ${i.unidadeComercial ?? i.unidade ?? ''} · ${i.motivoAvaria ?? ''}`;
      }),
    ),
  );

  readonly qtd = qtdPalete;

  ngOnInit(): void {
    this.api.doca(this.nunota).subscribe({ next: (d) => this.doca.set(d), error: () => undefined });
    this.entrada.listar().subscribe({ next: (l) => this.rec.set(l.find((r) => r.nunota === this.nunota) ?? null), error: () => undefined });
  }

  imprimir(): void {
    window.print();
  }
}
