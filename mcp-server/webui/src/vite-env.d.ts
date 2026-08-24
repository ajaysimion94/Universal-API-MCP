/// <reference types="vite/client" />

declare module "/pages/*.js" {
  export function mount(
    outlet: HTMLElement,
    context: {
      navigate: (url: string, replace?: boolean) => void;
      pathname: string;
      params: URLSearchParams;
    },
  ): void | (() => void) | Promise<void | (() => void)>;
}
