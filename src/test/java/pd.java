import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.HpisAlarmApplication;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import com.hpis.alarm.service.IAlarmPartialDischargeService;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = HpisAlarmApplication.class)
public class pd {

    @Autowired
    private AlarmMapper alarmMapper;

    @Autowired
    private IAlarmElectrolyticCellService iAlarmElectrolyticCellService;

    @Autowired
    private AlarmHandleMapper alarmHandleMapper;
    @Autowired
    private AlarmElectrolyticCellMapper alarmElectrolyticCellMapper;
    @Autowired
    private IAlarmPartialDischargeService alarmPartialDischargeService;

    private static final Snowflake snowflake = new Snowflake(5, 5);

    @Test
    public void exportTemps1111() throws IOException {
        AlarmQueryParameter alarmQueryParameter = new AlarmQueryParameter();
        alarmQueryParameter.setTenantId(67L);
        List<Map<String, Object>> maps = alarmPartialDischargeService.deviceAlarmOfDayByCustomer(alarmQueryParameter);
        System.out.println(maps);
    }

    @Test
    public void insert() {
        Date nowDate = DateUtils.getNowDate();

//        Date date = new Date(nowDate.getTime() + 100);
        //雪花主键

        //总数
        int a = 200 * 10000;


        for (int i = 0; i < (a / 10000) + 1; i++) {
            ArrayList<AlarmHandle> objects = new ArrayList<>();
            ArrayList<Alarm> alarms = new ArrayList<>();
            ArrayList<AlarmElectrolyticCell> electrolyticCells = new ArrayList<>();

            for (int j = 0; j <= 10000; j++) {
                Alarm alarm1 = new Alarm();
                long alarmId = snowflake.nextId();
                alarm1.setAlarmId(alarmId);
                alarm1.setAlarmType("" + RandomUtil.randomInt(0, 3));
                alarm1.setCreateTime(DateUtils.getNowDate());
                alarm1.setAlarmBegintime(new Date(nowDate.getTime() + 100*j*(i+1)));
                alarms.add(alarm1);
                if ((j % 5)>=4 ) {
                    AlarmHandle alarmHandle = new AlarmHandle();
                    AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
                    alarmElectrolyticCell.setGrooveNumber(RandomUtil.randomInt(0,88));
                    alarmHandle.setAlarmId(alarmId);
                    alarmHandle.setHandleStatus("0");
                    alarmHandle.setDelFlag("0");
                    objects.add(alarmHandle);
                    alarmElectrolyticCell.setAlarmId(alarmId);
                    electrolyticCells.add(alarmElectrolyticCell);
                }
            }
            System.out.println(alarms.get(0));
//            alarmMapper.insertAlarmList(alarms);
            alarmHandleMapper.insertAlarmHandelList(objects);
            iAlarmElectrolyticCellService.insertAlarmElectrolyticCellList(electrolyticCells);
        }

    }

    @Test
    public void selectECPage(){

        AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
        alarmElectrolyticCell.setAlarmType("2");
        alarmElectrolyticCell.setPageNum(1);
        alarmElectrolyticCell.setPageSize(20);
        Page<AlarmDetailEc> alarmDetailEcPage = iAlarmElectrolyticCellService.testSelect(alarmElectrolyticCell);
        System.out.println(alarmDetailEcPage.getSize());
    }


}
