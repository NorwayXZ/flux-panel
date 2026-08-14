import axios, { AxiosResponse } from "axios";

import { getPanelAddresses, isWebViewFunc } from "@/utils/panel";

interface PanelAddress {
  name: string;
  address: string;
  inx: boolean;
}

const setPanelAddressesFunc = (newAddress: PanelAddress[]) => {
  newAddress.forEach((item) => {
    if (item.inx) {
      baseURL = `${item.address}/api/v1/`;
      axios.defaults.baseURL = baseURL;
    }
  });
};

function getWebViewPanelAddress() {
  (window as any).setAddresses = setPanelAddressesFunc;
  getPanelAddresses("setAddresses");
}

let baseURL: string = "";

export const reinitializeBaseURL = () => {
  if (isWebViewFunc()) {
    getWebViewPanelAddress();
  } else {
    baseURL = import.meta.env.VITE_API_BASE
      ? `${import.meta.env.VITE_API_BASE}/api/v1/`
      : "/api/v1/";
    axios.defaults.baseURL = baseURL;
  }
};

reinitializeBaseURL();

interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data: T;
}

interface CacheEntry<T> {
  expiresAt: number;
  promise: Promise<ApiResponse<T>>;
  inFlight: boolean;
}

const responseCache = new Map<string, CacheEntry<unknown>>();
const DEFAULT_TIMEOUT_MS = Number(import.meta.env.VITE_API_TIMEOUT_MS || 30000);

function cacheKey(path: string, data: any): string {
  const token = window.localStorage.getItem("token") || "";

  return `${token}:${path}:${JSON.stringify(data)}`;
}

// 处理token失效的逻辑
function handleTokenExpired() {
  // 清除localStorage中的token
  window.localStorage.removeItem("token");
  window.localStorage.removeItem("role_id");
  window.localStorage.removeItem("name");

  // 跳转到登录页面
  if (window.location.pathname !== "/") {
    window.location.href = "/";
  }
}

// 检查响应是否为token失效
function isTokenExpired(response: ApiResponse) {
  return (
    response &&
    response.code === 401 &&
    (response.msg === "未登录或token已过期" ||
      response.msg === "无效的token或token已过期" ||
      response.msg === "无法获取用户权限信息")
  );
}

function errorMessage(error: any): string {
  if (error?.code === "ECONNABORTED") return "请求超时，请稍后重试";
  if (error?.response?.status === 502)
    return "后端服务暂时不可用（502），请稍后重试";
  if (error?.response?.status === 504) return "后端处理超时（504），请稍后重试";

  return error?.message || "网络请求失败";
}

const Network = {
  get: function <T = any>(
    path: string = "",
    data: any = {},
  ): Promise<ApiResponse<T>> {
    return new Promise(function (resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === "") {
        resolve({ code: -1, msg: " - 请先设置面板地址", data: null as T });

        return;
      }

      axios
        .get(path, {
          params: data,
          timeout: DEFAULT_TIMEOUT_MS,
          headers: {
            Authorization: window.localStorage.getItem("token"),
          },
        })
        .then(function (response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();

            return;
          }
          resolve(response.data);
        })
        .catch(function (error: any) {
          console.error("GET请求错误:", error);

          // 检查是否是401错误（token失效）
          if (error.response && error.response.status === 401) {
            handleTokenExpired();

            return;
          }

          resolve({ code: -1, msg: errorMessage(error), data: null as T });
        });
    });
  },

  post: function <T = any>(
    path: string = "",
    data: any = {},
  ): Promise<ApiResponse<T>> {
    return new Promise(function (resolve) {
      // 如果baseURL是默认值且是WebView环境，说明没有设置面板地址
      if (baseURL === "") {
        resolve({ code: -1, msg: " - 请先设置面板地址", data: null as T });

        return;
      }

      axios
        .post(path, data, {
          timeout: DEFAULT_TIMEOUT_MS,
          headers: {
            Authorization: window.localStorage.getItem("token"),
            "Content-Type": "application/json",
          },
        })
        .then(function (response: AxiosResponse<ApiResponse<T>>) {
          // 检查是否token失效
          if (isTokenExpired(response.data)) {
            handleTokenExpired();

            return;
          }
          resolve(response.data);
        })
        .catch(function (error: any) {
          console.error("POST请求错误:", error);

          // 检查是否是401错误（token失效）
          if (error.response && error.response.status === 401) {
            handleTokenExpired();

            return;
          }

          resolve({ code: -1, msg: errorMessage(error), data: null as T });
        });
    });
  },

  // Short-lived cache plus in-flight request de-duplication for read-only lists.
  // Callers can invalidate a path after a mutation so changes appear immediately.
  postCached: function <T = any>(
    path: string = "",
    data: any = {},
    ttlMs = 8000,
  ): Promise<ApiResponse<T>> {
    const key = cacheKey(path, data);
    const existing = responseCache.get(key);

    if (existing && (existing.inFlight || existing.expiresAt > Date.now())) {
      return existing.promise as Promise<ApiResponse<T>>;
    }

    const promise = Network.post<T>(path, data);

    responseCache.set(key, { expiresAt: 0, promise, inFlight: true });
    promise
      .then((response) => {
        if (response.code !== 0) {
          responseCache.delete(key);

          return;
        }
        const cached = responseCache.get(key);

        if (cached?.promise === promise) {
          responseCache.set(key, {
            expiresAt: Date.now() + ttlMs,
            promise,
            inFlight: false,
          });
        }
      })
      .catch(() => responseCache.delete(key));

    return promise;
  },

  mutate: function <T = any>(
    path: string = "",
    data: any = {},
    cachePaths: string[] = [],
  ): Promise<ApiResponse<T>> {
    return Network.post<T>(path, data).then((response) => {
      if (response.code === 0)
        cachePaths.forEach((cachePath) => Network.clearCache(cachePath));

      return response;
    });
  },

  clearCache: function (pathPrefix?: string): void {
    if (!pathPrefix) {
      responseCache.clear();

      return;
    }
    for (const key of responseCache.keys()) {
      if (key.includes(`:${pathPrefix}:`)) responseCache.delete(key);
    }
  },
};

export default Network;
