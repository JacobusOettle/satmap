// Vite's `?raw` imports: a file's text (the test certificates in test/fixtures/apple).
declare module "*?raw" {
  const text: string;
  export default text;
}
