export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string
  ) {
    super(message);
    this.name = "HttpError";
  }
}

export function notFound(message = "Resource not found") {
  return new HttpError(404, message);
}
