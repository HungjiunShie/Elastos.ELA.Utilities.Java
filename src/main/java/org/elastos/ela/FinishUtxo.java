package org.elastos.ela;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.elastos.elaweb.ElaController;
import org.elastos.framework.rpc.Rpc;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FinishUtxo {

    private static String RPCURL ;
    private static int FEE ;
    private static int CONFIRMATION ;

    private static long utxoAmount;
    private static List<UTXOTxInput> inputList;

    public static String txHash;
    public static Boolean STATE = true;

    /**
     * 整合utxo
     * @param privates
     * @param outputs
     * @param ChangeAddress
     * @return
     */
    public static String finishUtxo(List<String> privates , LinkedList<TxOutput> outputs , String ChangeAddress) throws IOException {

        //去重
        ArrayList<String> privateList = new ArrayList<String>(new HashSet<String>(privates));

        //获取utxo
        Map<String,String[]> params = new HashMap<String,String[]>();
        String[] addressList = new String[privateList.size()];
        for (int i = 0 ; i < privateList.size() ; i++){
            String address = Ela.getAddressFromPrivate(privateList.get(i));
            addressList[i] = address;
        }
        params.put("addresses",addressList);
        String state = getConfig_url();
        if (state != null){
            STATE = false;
            return state;
        }

//        System.out.println("==================== 通过地址查询uxto  ====================");
        String utxo = Rpc.call_("listunspent",params,RPCURL);
        String flag = getUtxo(utxo , outputs , ChangeAddress);
        if (flag.equals("ok")){
            RawTx rawTx = SignTxAbnormal.singleSignTx(inputList.toArray(new UTXOTxInput[inputList.size()]), outputs.toArray(new TxOutput[outputs.size()]), privates);
            txHash = rawTx.getTxHash();
            return rawTx.getRawTxString();
        }else {
            return flag;
        }
    }

    /**
     * 获取可用utxo
     * @param utxo
     * @param outputs
     * @param ChangeAddress
     * @return
     */
    public static String getUtxo(String utxo , LinkedList<TxOutput> outputs , String ChangeAddress){

        String flag = "ok";

        JSONObject jsonObject = JSONObject.fromObject(utxo);
        String error = jsonObject.getString("error");
        if (error != "null"){
            JSONObject jsonError = JSONObject.fromObject(error);
            String message = jsonError.getString("message");
            System.out.println("获取utxo失败 ：" + message);
            return flag = "Getting utxo failure , " +message;
        }

        JSONArray jsonArray = jsonObject.getJSONArray("result");
        List<UTXOInputSort> UTXOInputList = new ArrayList<UTXOInputSort>();
        for (int i=0 ; i < jsonArray.size() ; i++){
            JSONObject result = (JSONObject) jsonArray.get(i);
            String txid = result.getString("txid");
            String address = result.getString("address");
            int vout = result.getInt("vout");

            String blockHash = getBlockHash(txid);

            if (blockHash != null){
                boolean boo = unlockeUtxo(blockHash, txid , vout);
                if (boo){
                    UTXOInputList.add(new UTXOInputSort(txid,address,vout,utxoAmount));
                }
            }
        }

        Collections.sort(UTXOInputList);

        //outputs.amount > 可用utxo.amout
        inputList = new LinkedList<UTXOTxInput>();

        //计算所有的output金额
        long outputValue = 0;
        for (int k = 0 ; k < outputs.size() ; k++){
            TxOutput output = outputs.get(k);
            long value = output.getValue();
            outputValue += value;
        }

        //计算所有的input金额
        long inputValue = 0;
        for (int j = 0 ; j < UTXOInputList.size() ; j++){
            UTXOInputSort input = UTXOInputList.get(j);
            String inputTxid = input.getTxid();
            String inputAddress = input.getAddress();
            int inputVont = input.getVont();

            inputValue += input.getAmount();
            inputList.add(new UTXOTxInput(inputTxid,inputVont,"",inputAddress));

            //input金额够用
            if (inputValue >= outputValue + FEE){
                break;
            }
        }

        if (inputValue >= outputValue + FEE){
            //计算找零金额
            long ChangeValue = inputValue - outputValue - FEE;
            outputs.add(new TxOutput(ChangeAddress, ChangeValue));
            return "ok";
        }else {
            System.out.println("utxo不足，value = " +inputValue);
            flag = "Utxo deficiency , inputValue : " + inputValue + " , outputValue :" + outputValue;
        }
        return flag;
    }

    /**
     * 解析区块信息判断可用utxo
     * UTXO锁验证步骤：
     * 1.判断所有的input 是否引用包含 UTXO 锁 （OutputLock > 0）的 UTXO，如果没有引用，则返回 true;
     * 2.判断引用了 UTXO 锁的 Input 的 Sequence 是否等于 0xfffffffe,如果不相等，返回 false；
     * 3.判断交易的 TimeLock 是否大于所有 UTXO 的 OutputLock 的值，如果不大于，返回 false;
     * 4.验证通过，返回 ture.
     * @param txid
     * @param vout
     * @return
     */
    public static Boolean unlockeUtxo(String blockHash , String txid , int vout){
        LinkedHashMap<String, Object> paramsMap = new LinkedHashMap<String, Object>();
        paramsMap.put("blockhash",blockHash);
        paramsMap.put("verbosity",2);

//        System.out.println("==================== 通过区块Hash查询区块信息  ====================");
        String block = Rpc.call_("getblock" ,paramsMap, RPCURL);

        JSONObject jsonObject = JSONObject.fromObject(block);
        String error = jsonObject.getString("error");
        if (error != "null"){
            JSONObject jsonError = JSONObject.fromObject(error);
            String message = jsonError.getString("message");
            System.out.println("获取区块信息失败 ：" + message);
            return false;
        }
        JSONObject results = jsonObject.getJSONObject("result");
        JSONArray  txArray = results.getJSONArray("tx");

        for (int i =0 ; i < txArray.size() ; i++){
            JSONObject tx =(JSONObject) txArray.get(i);
            String txHash = tx.getString("txid");
            if (txHash.equals(txid)){
                long locktime = tx.getLong("locktime");
//                System.out.println("locktime = " + locktime);

                // 步骤 1
                JSONArray voutJson = tx.getJSONArray("vout");
                JSONObject output = (JSONObject) voutJson.get(vout);
                long outputlock = output.getLong("outputlock");
//                System.out.println("outputlock = " + outputlock);

                String value = output.getString("value");
                long valueLong = Util.IntByString(value);
                utxoAmount = valueLong;
                if (outputlock == 0){
                    return true;
                }
                System.out.println("锁仓 txid : " + txid );
//                JSONArray vinJson = tx.getJSONArray("vin");
//
                  // 步骤 2
//                for(int j = 0 ; j < vinJson.size() ; i++){
//                    JSONObject vin =(JSONObject) vinJson.get(j);
//                    long sequence = vin.getLong("sequence");
//                    System.out.println("sequence = " + sequence);
//                    if (sequence == 0xfffffffe || sequence == 0){
//                        return true;
//                    }
//                }
                  // 步骤 3
//                System.out.println("uxto locked , txid :" + txid);
//                if (locktime > outputlock){
//                    return true;
//                }
            }
        }
        return false;
    }


    /**
     * 通过交易ID获取区块Hash
     * @param txid
     * @return
     */
    public static String getBlockHash(String txid){
        LinkedHashMap<String, Object> paramsMap = new LinkedHashMap<String, Object>();
        paramsMap.put("txid",txid);
        paramsMap.put("verbose",true);

//        System.out.println("==================== 通过txid查询区块Hash  ====================");
        String Transcation = Rpc.call_("getrawtransaction",paramsMap , RPCURL);
        JSONObject jsonObject = JSONObject.fromObject(Transcation);
        String error = jsonObject.getString("error");
        if (error != "null"){
            JSONObject jsonError = JSONObject.fromObject(error);
            String message = jsonError.getString("message");
            System.out.println("获取区块信息失败 ：" + message);
            return "";
        }
//        JSONArray jsonArray = jsonObject.getJSONArray("result");
        JSONObject result = jsonObject.getJSONObject("result");
        int confirmations = result.getInt("confirmations");
        if (confirmations >= CONFIRMATION){
            return result.getString("blockhash");
        }
        return null;
    }

    /**
     * 获取java-config.json配置文件信息
     * @throws IOException
     */
    public static String getConfig_url(){
        try {
            File directory = new File ("");
            String courseFile = directory.getCanonicalPath();
//        File file = new File(courseFile + "/src/main/resources/java-config.json");
            File file = new File(courseFile + "/java-config.json");
            String content = FileUtils.readFileToString(file,"UTF-8");
            JSONObject jsonObject = JSONObject.fromObject(content);

            String state = ElaController.checkFeeAndHost("genRawTransactionByPrivateKey",jsonObject);
            if (state != null){
                STATE = false;
                return state;
            }

            String host = jsonObject.getString("Host");
            FEE = jsonObject.getInt("Fee");
            RPCURL = "http://" + host;
            CONFIRMATION = jsonObject.getInt("Confirmation");
            if (CONFIRMATION == 0){
                CONFIRMATION = 16;
            }
        }catch (IOException e){
            return ElaController.error("genRawTransactionByPrivateKey",e.toString());
        }
        return null;
    }
}


