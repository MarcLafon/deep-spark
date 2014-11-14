/*
 * Copyright 2014, Stratio.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.deep.examples.java;

import com.stratio.deep.cassandra.config.CassandraConfigFactory;
import com.stratio.deep.cassandra.config.CassandraDeepJobConfig;
import com.stratio.deep.commons.entity.Cells;
import com.stratio.deep.core.context.DeepSparkContext;
import com.stratio.deep.utils.ContextProperties;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WrittingRddWithMinToCassandra {
    private static final Logger LOG = Logger.getLogger(WrittingRddWithMinToCassandra.class);
    public static List<Tuple2<String, Integer>> results;

    private WrittingRddWithMinToCassandra() {
    }

    /**
     * Application entry point.
     *
     * @param args the arguments passed to the application.
     */
    public static void main(String[] args) {
        doMain(args);
    }

    /**
     * This is the method called by both main and tests.
     *
     * @param args
     */
    public static void doMain(String[] args) {
        String job = "java:saveWithQueryBuilder";

        String keyspaceName = "test";
        String inputTableName = "tweets2";
        String statsTableName = "counters2";

        // Creating the Deep Context where args are Spark Master and Job Name
        ContextProperties p = new ContextProperties(args);
        DeepSparkContext deepContext = new DeepSparkContext(p.getCluster(), job, p.getSparkHome(), p.getJars());


        // --- INPUT RDD
        CassandraDeepJobConfig<Cells> inputConfig = CassandraConfigFactory.create()
                .host(p.getCassandraHost()).cqlPort(p.getCassandraCqlPort()).rpcPort(p.getCassandraThriftPort())
                .keyspace(keyspaceName).table(inputTableName)
                .initialize();

        JavaRDD<Cells> inputRDD = deepContext.createJavaRDD(inputConfig);



        long initTime = System.currentTimeMillis();

                // --- STATS RDD
        CassandraDeepJobConfig<Cells> statsConfig = CassandraConfigFactory.create()
                .host(p.getCassandraHost()).cqlPort(p.getCassandraCqlPort()).rpcPort(p.getCassandraThriftPort())
                .keyspace(keyspaceName).table(statsTableName)
                .initialize();

        JavaRDD<Cells> statsRDD = deepContext.createJavaRDD(statsConfig);

        System.out.println("**********************"+statsRDD.count()+System.currentTimeMillis());
        long timeCreateRDD = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();



        final String [] commonPrimaryKeys = new String[]{"tweet_id"};
        final String namespaceA = keyspaceName+"."+inputTableName;

        JavaPairRDD<List<Object>,Cells> mappedRddA = inputRDD.mapToPair(new PairFunction<Cells, List<Object>, Cells>() {
            @Override
            public Tuple2<List<Object>, Cells> call(Cells cells) throws Exception {
                List<Object> pkValues = new ArrayList<Object>(commonPrimaryKeys.length);
                //TODO Cells => delete pKey??
                for (String pKey:commonPrimaryKeys){
                    pkValues.add(cells.getCellByName(namespaceA,pKey).getValue());
                }

                return new Tuple2<List<Object>, Cells>(pkValues,cells);
            }
        });


        System.out.println("**********************"+mappedRddA.count()+System.currentTimeMillis());
        long timeMappedRDDA = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();

        final String statsNamespace = keyspaceName+"."+statsTableName;

        JavaPairRDD<List<Object>,Cells> mappedRddB = statsRDD.mapToPair(new PairFunction<Cells, List<Object>, Cells>() {
            @Override
            public Tuple2<List<Object>, Cells> call(Cells cells) throws Exception {
                List<Object> pkValues = new ArrayList<Object>(commonPrimaryKeys.length);
                for (String pKey:commonPrimaryKeys){
                    pkValues.add(cells.getCellByName(statsNamespace,pKey).getValue());
                }
                return new Tuple2<List<Object>, Cells>(pkValues,cells);
            }
        });


        System.out.println("**********************"+mappedRddB.count()+System.currentTimeMillis());
        long timeMappedRDDB = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();

        JavaPairRDD<List<Object>, Tuple2<Cells, Cells>> join = mappedRddA.join(mappedRddB);

        System.out.println("**********************"+join.count()+System.currentTimeMillis());
        long timeJoin = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();


        final String minField = "favorite_count";

        //=> JOIN (RIGHT => stored stats) (LEFT=>Streaming)
        JavaRDD<Cells> matchedCells = join.map(new Function<Tuple2<List<Object>,Tuple2<Cells,Cells>>, Cells>() {
            @Override
            public Cells call(Tuple2<List<Object>, Tuple2<Cells, Cells>> listTuple2Tuple2) throws Exception {
                Cells preparedCell;
                boolean isCurrentLower = listTuple2Tuple2._2()._2().getLong(statsNamespace,minField) < listTuple2Tuple2._2()._1().getLong(namespaceA,minField);

                preparedCell = isCurrentLower ? null :  listTuple2Tuple2._2()._1();
                return preparedCell;
            }
        });

        System.out.println("**********************"+matchedCells.count()+System.currentTimeMillis());
        long timeMap = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();


        //=> JOIN (RIGHT => stored stats) (LEFT=>Streaming)
        JavaRDD<Cells> filterCells = matchedCells.filter(new Function<Cells, Boolean>() {
            @Override
            public Boolean call(Cells cells) throws Exception {
                return (cells == null);
            }
        });



        System.out.println("**********************"+filterCells.count()+System.currentTimeMillis());
        long timeFilter = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();


        //numMatched > 4 siempre=> si no hay rdd => error
        deepContext.saveRDD(filterCells.rdd(), statsConfig);

        System.out.println("**********************"+matchedCells.count()+System.currentTimeMillis());
        long timeSave = System.currentTimeMillis() - initTime;
        initTime = System.currentTimeMillis();

        System.out.println("createRDD"+timeCreateRDD+"\n"+
                "mapA"+timeMappedRDDA+"\n"+
                "mapB"+timeMappedRDDB+"\n"+
                "join"+timeJoin+"\n"+
                "map"+timeMap+"\n"+
                "filter"+timeFilter+"\n"+
                "save"+timeSave
        );

        deepContext.stop();
    }
}
