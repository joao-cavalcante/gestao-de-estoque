import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { DownloadItem } from './downloads.model';
import { OqPanelSectionComponent } from '../conferencia/oq-panel-section/oq-panel-section.component';
import { OqIconComponent } from '../shared/icons/oq-icon.component';

@Component({
  selector: 'app-downloads',
  standalone: true,
  imports: [OqPanelSectionComponent, OqIconComponent],
  templateUrl: './downloads.component.html',
})
export class DownloadsComponent implements OnInit {
  private readonly http = inject(HttpClient);

  itens = signal<DownloadItem[]>([]);
  carregando = signal(true);

  ngOnInit(): void {
    this.http.get<DownloadItem[]>('/api/downloads').subscribe({
      next: (itens) => {
        this.itens.set(itens);
        this.carregando.set(false);
      },
      error: () => this.carregando.set(false),
    });
  }

  baixar(item: DownloadItem): void {
    window.open(`/api/downloads/${item.id}`, '_blank');
  }
}
