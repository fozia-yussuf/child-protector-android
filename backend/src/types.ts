import type { Request } from "express";

export type Role = "ADMIN" | "DRIVER" | "PARENT";

export interface Principal {
  id: string;
  username: string;
  role: Role;
  sessionHash: string;
  expiresAt: Date;
}

export interface AuthenticatedRequest extends Request {
  principal: Principal;
}

export function principalOf(request: Request): Principal {
  return (request as AuthenticatedRequest).principal;
}
