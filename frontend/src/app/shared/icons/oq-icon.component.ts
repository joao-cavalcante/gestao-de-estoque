import { Component, Input } from '@angular/core';

export type OqIconName =
  | 'circle' | 'circle-filled' | 'circle-alert' | 'gear' | 'check' | 'triangle'
  | 'search' | 'filter' | 'sync' | 'barcode' | 'x' | 'arrow-left'
  | 'receipt' | 'handshake' | 'badge' | 'clock' | 'list-check'
  | 'menu' | 'box' | 'user' | 'scale' | 'download' | 'building' | 'logout'
  | 'sun' | 'moon';

/**
 * Ícones inline SVG, sem dependência de lib externa (@angular/material etc.)
 * — reescrita do zero, sem herdar a stack do sistema anterior. Usa
 * `currentColor` em `stroke`/`fill`, então o componente pai controla a cor
 * via CSS `color`. Compartilhado entre todas as telas (Fila de Tarefas,
 * Conferência, ...).
 *
 * A forma do ícone é o que comunica severidade (círculo cheio = crítico,
 * triângulo = atenção, check = concluído) — nunca só a cor, por
 * acessibilidade a daltonismo.
 */
@Component({
  selector: 'oq-icon',
  standalone: true,
  host: { style: 'display: inline-flex;' },
  template: `
    <svg
      [attr.width]="size"
      [attr.height]="size"
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      @switch (name) {
        @case ('circle') {
          <circle cx="12" cy="12" r="7" stroke="currentColor" stroke-width="2" />
        }
        @case ('circle-filled') {
          <circle cx="12" cy="12" r="7" fill="currentColor" />
        }
        @case ('circle-alert') {
          <circle cx="12" cy="12" r="9" fill="currentColor" />
          <path d="M12 8v4.5" stroke="#fff" stroke-width="2" stroke-linecap="round" />
          <circle cx="12" cy="15.8" r="1" fill="#fff" />
        }
        @case ('gear') {
          <path
            d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"
            stroke="currentColor" stroke-width="2"
          />
          <path
            d="M19.4 13.5c.04-.33.06-.66.06-1s-.02-.67-.06-1l1.86-1.45a.6.6 0 0 0 .14-.77l-1.76-3.05a.6.6 0 0 0-.73-.26l-2.2.88a7.6 7.6 0 0 0-1.73-1l-.33-2.34a.6.6 0 0 0-.6-.51h-3.5a.6.6 0 0 0-.6.51l-.33 2.34c-.63.24-1.2.58-1.73 1l-2.2-.88a.6.6 0 0 0-.73.26L2.6 9.28a.6.6 0 0 0 .14.77l1.86 1.45c-.04.33-.06.66-.06 1s.02.67.06 1L2.74 14.9a.6.6 0 0 0-.14.77l1.76 3.05a.6.6 0 0 0 .73.26l2.2-.88c.53.42 1.1.76 1.73 1l.33 2.34a.6.6 0 0 0 .6.51h3.5a.6.6 0 0 0 .6-.51l.33-2.34c.63-.24 1.2-.58 1.73-1l2.2.88a.6.6 0 0 0 .73-.26l1.76-3.05a.6.6 0 0 0-.14-.77L19.4 13.5Z"
            stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"
          />
        }
        @case ('check') {
          <path d="M5 12.5 9.5 17 19 7" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('triangle') {
          <path
            d="M12 4.5 21 19.5H3L12 4.5Z"
            stroke="currentColor" stroke-width="2" stroke-linejoin="round"
          />
          <path d="M12 10v4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <circle cx="12" cy="16.7" r="0.9" fill="currentColor" />
        }
        @case ('search') {
          <circle cx="10.5" cy="10.5" r="6" stroke="currentColor" stroke-width="2" />
          <path d="m20 20-4.8-4.8" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        }
        @case ('filter') {
          <path d="M4 6h16M7.5 12h9M10.5 18h3" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        }
        @case ('sync') {
          <path d="M4 12a8 8 0 0 1 13.66-5.66L20 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <path d="M20 4v4h-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M20 12a8 8 0 0 1-13.66 5.66L4 16" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <path d="M4 20v-4h4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('barcode') {
          <path d="M4 5v14M8 5v14M11 5v14M13 5v14M17 5v14M20 5v14" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('x') {
          <path d="M6 6l12 12M18 6 6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
        }
        @case ('arrow-left') {
          <path d="M19 12H5M11 6l-6 6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('receipt') {
          <path d="M6 3h12v18l-2.5-1.5L13 21l-2.5-1.5L8 21l-2-1.5V3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
          <path d="M9 8h6M9 12h6M9 16h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('handshake') {
          <path d="M2 12.5 6 9l4 3 3-2.5 3 2 3-2.5 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M6 9v9M18 9v9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('badge') {
          <rect x="4" y="5" width="16" height="15" rx="2" stroke="currentColor" stroke-width="1.6" />
          <circle cx="12" cy="10.5" r="2.5" stroke="currentColor" stroke-width="1.6" />
          <path d="M8 17c.6-1.8 2-2.8 4-2.8s3.4 1 4 2.8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('clock') {
          <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.6" />
          <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('list-check') {
          <path d="M9 6h11M9 12h11M9 18h11" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          <path d="m3 6 1.3 1.3L7 4.7M3 12l1.3 1.3L7 10.7M3 18l1.3 1.3L7 16.7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('menu') {
          <path d="M4 6h16M4 12h16M4 18h16" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        }
        @case ('box') {
          <path d="M3.5 8 12 3.5 20.5 8 12 12.5 3.5 8Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
          <path d="M3.5 8v8L12 20.5V12.5M20.5 8v8L12 20.5" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
        }
        @case ('user') {
          <circle cx="12" cy="8" r="3.5" stroke="currentColor" stroke-width="1.6" />
          <path d="M4.5 20c1-4 4-6 7.5-6s6.5 2 7.5 6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('scale') {
          <path d="M12 3v18M8 21h8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          <path d="M12 5 5 8l3.2 6.2a4 4 0 0 0 7.6 0L19 8Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
          <path d="M5 8h7M19 8h-7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        }
        @case ('download') {
          <path d="M12 3v12M7.5 10.5 12 15l4.5-4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M4 17v2.5A1.5 1.5 0 0 0 5.5 21h13a1.5 1.5 0 0 0 1.5-1.5V17" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('building') {
          <rect x="5" y="3.5" width="10" height="17" rx="1" stroke="currentColor" stroke-width="1.6" />
          <path d="M15 10h4v10.5M8 7.5h1M11 7.5h1M8 11h1M11 11h1M8 14.5h1M11 14.5h1" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
          <path d="M9 20.5v-3.7h2v3.7" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        }
        @case ('logout') {
          <path d="M9 4H6a1.5 1.5 0 0 0-1.5 1.5v13A1.5 1.5 0 0 0 6 20h3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          <path d="M13.5 8 17.5 12l-4 4M17.5 12h-11" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        }
        @case ('sun') {
          <circle cx="12" cy="12" r="4" stroke="currentColor" stroke-width="1.8" />
          <path d="M12 2.5v2.5M12 19v2.5M4.4 4.4l1.8 1.8M17.8 17.8l1.8 1.8M2.5 12H5M19 12h2.5M4.4 19.6l1.8-1.8M17.8 6.2l1.8-1.8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        }
        @case ('moon') {
          <path d="M20 14.2A8.5 8.5 0 1 1 9.8 4a6.6 6.6 0 0 0 10.2 10.2Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" />
        }
      }
    </svg>
  `,
})
export class OqIconComponent {
  @Input() name: OqIconName = 'circle';
  @Input() size = 16;
}
