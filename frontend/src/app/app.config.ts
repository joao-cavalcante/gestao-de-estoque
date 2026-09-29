import { ApplicationConfig, LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './auth/auth.interceptor';
import { lockInterceptor } from './separacao/lock.interceptor';

// pt-BR no app inteiro: sem isto o pipe `number` formatava no padrão americano
// ("1,980.000" em vez de "1.980,000") — confundia o operador no pop-up de peso.
registerLocaleData(localePt, 'pt-BR');

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor, lockInterceptor])),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
  ],
};
