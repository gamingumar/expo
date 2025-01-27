import { NetworkState } from './Network.types';
export declare function getNetworkStateAsync(): Promise<NetworkState>;
export declare function getIpAddressAsync(): Promise<string>;
export declare function getMacAddressAsync(interfaceName?: string): Promise<string>;
export declare function isAirplaneModeEnabledAsync(): Promise<boolean>;
