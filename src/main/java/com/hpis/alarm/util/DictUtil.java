package com.hpis.alarm.util;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.utils.SpringUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.system.api.domain.SysDictData;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公共字典工具类
 */
public class DictUtil {

    /**
     * 通过字典类型和键值获得标签内容
     * 不建议在循环中使用
     * @param type
     * @param value
     * @return
     */
    public static String getDictLabelByTypeAndValue(String type, String value) {
        List<SysDictData> sysDictDataList = getDictCache(type);
        if (sysDictDataList.size() > 0) {
            Map<String, String> map = sysDictDataList.parallelStream().collect(Collectors
                    .toMap(SysDictData::getDictValue, SysDictData::getDictLabel, (key1, key2) -> key2));
            if (map.containsKey(value)) {
                return map.get(value);
            }
        }
        return "";
    }

    /**
     * 从字典列表中获取键值对应的标签
     * 适用于列表翻译字典
     * @param sysDictDataList
     * @param value
     * @return
     */
    public static String getDictLabelByValue(List<SysDictData> sysDictDataList, String value) {
        if (sysDictDataList.size() > 0) {
            Map<String, String> map = sysDictDataList.parallelStream().collect(Collectors
                    .toMap(SysDictData::getDictValue, SysDictData::getDictLabel, (key1, key2) -> key2));
            if (map.containsKey(value)) {
                return map.get(value);
            }
        }
        return "";
    }

    /**
     * 获取字典缓存
     *
     * @param key 参数键
     * @return dictDatas 字典数据列表
     */
    public static List<SysDictData> getDictCache(String key)
    {
        Object cacheObj = SpringUtils.getBean(RedisService.class).getCacheObject(getCacheKey(key));
        if (StringUtils.isNotNull(cacheObj))
        {
            List<SysDictData> dictDatas = StringUtils.cast(cacheObj);
            return dictDatas;
        }
        return null;
    }

    /**
     * 设置cache key
     *
     * @param configKey 参数键
     * @return 缓存键key
     */
    public static String getCacheKey(String configKey)
    {
        return Constants.SYS_DICT_KEY + configKey;
    }
}
