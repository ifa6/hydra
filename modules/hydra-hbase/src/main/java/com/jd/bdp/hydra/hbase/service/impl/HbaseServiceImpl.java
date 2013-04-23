/*
 * Copyright jd
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.jd.bdp.hydra.hbase.service.impl;

import com.alibaba.fastjson.JSON;
import com.jd.bdp.hydra.Annotation;
import com.jd.bdp.hydra.BinaryAnnotation;
import com.jd.bdp.hydra.Endpoint;
import com.jd.bdp.hydra.Span;
import com.jd.bdp.hydra.hbase.service.HbaseService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/*
trace:
rowkey:serviceId+traceId//
familColume:Span
Qualifier:{
	spanId1+0:
	spanId2+1:
	spanId3+0:
	spanId4+1:
}

durationIndex
rowkey:serviceId+duration
familColume:trace
Qualifier:{
	traceId
}

annotationIndex
rowkey:serviceId+key+value
familColume:trace
Qualifier:{
	traceId
}
 */
public class HbaseServiceImpl extends HbaseUtils implements HbaseService {

    public void createTable() {
        try {
            HBaseAdmin hBaseAdmin = new HBaseAdmin(conf);
            if (!hBaseAdmin.tableExists(duration_index)) {
                HTableDescriptor hTableDescriptor = new HTableDescriptor(duration_index);
                hTableDescriptor.addFamily(new HColumnDescriptor(duration_index_family_column));
                hBaseAdmin.createTable(hTableDescriptor);
            }
            if (!hBaseAdmin.tableExists(TR_T)) {
                HTableDescriptor hTableDescriptor = new HTableDescriptor(TR_T);
                hTableDescriptor.addFamily(new HColumnDescriptor());
                hBaseAdmin.createTable(hTableDescriptor);
            }
            if (!hBaseAdmin.tableExists(ann_index)) {
                HTableDescriptor hTableDescriptor = new HTableDescriptor(trace_family_column);
                hTableDescriptor.addFamily(new HColumnDescriptor(ann_index_family_column));
                hBaseAdmin.createTable(hTableDescriptor);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void addSpan(Span span) throws IOException {
        String rowkey = String.valueOf(span.getTraceId());
        Put put = new Put(rowkey.getBytes());
        String jsonValue = JSON.toJSONString(span);
        String spanId = String.valueOf(span.getId());
        if (isTopAnntation(span)) {
            spanId = spanId + "C";
        } else {
            spanId = spanId + "S";
        }
        HTableInterface htable = null;
        try {
            put.add(trace_family_column.getBytes(), spanId.getBytes(), jsonValue.getBytes());
            htable = POOL.getTable(TR_T);
            htable.put(put);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(htable != null){
                htable.close();
            }
        }
    }


    public void annotationIndex(Span span) {
        List<Annotation> alist = span.getAnnotations();
        List<Put> putlist = new ArrayList<Put>();
        for (Annotation a : alist) {
            String rowkey = a.getHost().getServiceName() + ":" + System.currentTimeMillis() + ":" + a.getValue();
            Put put = new Put(rowkey.getBytes());
            put.add(ann_index_family_column.getBytes(), "traceId".getBytes(), long2ByteArray(span.getTraceId()));
            putlist.add(put);
        }

        for (BinaryAnnotation b : span.getBinaryAnnotations()) {
            String rowkey = b.getHost().getServiceName() + ":" + System.currentTimeMillis() + ":" + b.getKey();
            Put put = new Put(rowkey.getBytes());
            put.add(ann_index_family_column.getBytes(), "traceId".getBytes(), long2ByteArray(span.getTraceId()));
            putlist.add(put);
        }

        HTableInterface htable = null;
        try {
            htable = POOL.getTable(ann_index);
            htable.put(putlist);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(htable != null){
                try {
                    htable.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    public void durationIndex(Span span) {
        List<Annotation> alist = span.getAnnotations();
        Annotation cs = getCsAnnotation(alist);
        Annotation cr = getCrAnnotation(alist);
        if (cs != null) {
            long duration = cs.getTimestamp() - cr.getTimestamp();
            String rowkey = cs.getHost().getServiceName() + ":" + cs.getTimestamp();
            Put put = new Put(rowkey.getBytes());
            //rowkey:serviceId:csTime
            //每列的timestamp为duration
            //每列列名为traceId，值为1（用来区分1ms内的跟踪）
            put.add(duration_index_family_column.getBytes(), long2ByteArray(span.getTraceId()), duration,  "1".getBytes());
            HTableInterface htable = null;
            try {
                htable = POOL.getTable(duration_index);
                htable.put(put);
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                if(htable != null){
                    try {
                        htable.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


}