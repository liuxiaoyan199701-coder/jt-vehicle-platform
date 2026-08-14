declare namespace Api {
  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
      accessTokenExpiresAt: string;
      refreshTokenExpiresAt: string;
    }

    interface RoleSummary {
      id: number;
      code: string;
      name: string;
      builtin: boolean;
    }

    interface UserInfo {
      userId: string;
      userName: string;
      displayName?: string | null;
      /** 平台账号为 null；租户账号为其租户标识 */
      tenantId?: number | null;
      tenantName?: string | null;
      /** 是否平台账号（跨租户） */
      platform?: boolean;
      roles: string[];
      roleDetails?: RoleSummary[];
      /** 权限码集合。菜单与按钮据此呈现，真正的拦截在后端 */
      permissions?: string[];
      buttons: string[];
    }
  }
}
