import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MapaSeparacaoDto } from './mapa-separacao.model';

@Injectable({ providedIn: 'root' })
export class MapaSeparacaoService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/mapa-separacao';

  consultar(ordemCarga: number): Observable<MapaSeparacaoDto> {
    return this.http.get<MapaSeparacaoDto>(`${this.baseUrl}/${ordemCarga}`);
  }
}
