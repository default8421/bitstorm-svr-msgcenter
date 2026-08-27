package cn.bitoffer.msgcenter.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLUtil。
 *
 * @author LQH
 */
public class SQLUtil {

    public static String convertListToSQLString(List<String> list) {
        // 创建一个StringBuilder来构建SQL字符串
        StringBuilder sqlString = new StringBuilder();

        // 如果是空列表则直接返回空字符串
        if (list == null || list.isEmpty()) {
            return "";
        }

        // 遍历列表中的每个元素，并将其作为SQL的单个项，
        // 将它追加到SQL字符串的后面（首次则不用添加逗号），并在最后一个后面加上关闭的括号
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sqlString.append(","); // 除了第一个元素外，其他元素前都加逗号
            }
            sqlString.append("'"); // 为每个项加单引号
            sqlString.append(list.get(i)); // 添加元素的值
            sqlString.append("'"); // 每个项后加单引号
        }

        // 添加开头的左括号和结尾的右括号
        sqlString.insert(0, "("); // 在开始处插入左括号
        sqlString.append(")"); // 在末尾添加右括号

        // 返回构造好的SQL字符串
        return sqlString.toString();
    }

}