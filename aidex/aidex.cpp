/**
 * A BLE client example that is rich in capabilities.
 * There is a lot new capabilities implemented.
 * author unknown
 * updated by chegewara
 */
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <NimBLEAdvertisedDevice.h>
#include "NimBLEEddystoneTLM.h"
#include "NimBLEBeacon.h"
#include <Adafruit_NeoPixel.h>

TaskHandle_t blink_blue_,blink_red_,blink_green_;
void blink_blue();
void blink_red();
void blink_green();
void stop_blinking();
int last_code;
#define PIN_NEO_PIXEL  48   // Arduino pin that connects to NeoPixel

Adafruit_NeoPixel NeoPixel(1, PIN_NEO_PIXEL, NEO_GRB + NEO_KHZ800);
const int POSITIONAL_CORRECTION=2;

struct sensor_sample {
  double glucose_mmol;
  int sample_age;
  int phase;
};

int         scanTime = 5 * 1000; // In milliseconds
NimBLEScan* pBLEScan;
// The remote service we wish to connect to.
static BLEUUID serviceUUID("0000f000-0000-1000-8000-00805f9b34fb");
//characteristic uuid 0000F001-0000-1000-8000-00805F9B34FB
//service uuid 0000F000-0000-1000-8000-00805F9B34FB
// The characteristic of the remote service we are interested in.
//static BLEUUID    charUUID("0000f001-0000-1000-8000-00805f9b34fb");
class ScanCallbacks : public NimBLEScanCallbacks {
    void onResult(const NimBLEAdvertisedDevice* advertisedDevice) override {
    

       if (advertisedDevice->isAdvertisingService(serviceUUID)){
           
             if (advertisedDevice->haveManufacturerData() == true) {
               std::string strManufacturerData = advertisedDevice->getManufacturerData();
              if (advertisedDevice->haveName()) {
                Serial.print("Device name: ");
                Serial.println(advertisedDevice->getName().c_str());
                Serial.println("");
            }
             Serial.printf("strManufacturerData: %d ", strManufacturerData.length());
//////////DEBUGGING PURPOSES////////////////
              Serial.printf("\n");
                int count[strManufacturerData.length()];
                for (int i = 0; i < strManufacturerData.length(); i++) {
                   std::string add_s;
                  for(int j=1;j<std::to_string(i).length();j++)
                    add_s+=" ";
                    Serial.printf("[%d%s]",strManufacturerData[i],add_s.c_str());
                    count[i]=std::to_string(strManufacturerData[i]).length();
                }
                  Serial.printf("\n");
                  for (int i = 0; i < strManufacturerData.length(); i++) {
                    std::string add_s;
                    for(int j=1;j<count[i];j++)
                    add_s+=" ";

                    Serial.printf("[%d%s]",i,add_s.c_str());
                }
                Serial.printf("\n");
//////////DEBUGGING PURPOSES////////////////
              sensor_sample sample{
                    .glucose_mmol = (double)strManufacturerData[POSITIONAL_CORRECTION+10]/10.0f,
                    .sample_age= strManufacturerData[POSITIONAL_CORRECTION+1]/6,
                    .phase=  strManufacturerData[POSITIONAL_CORRECTION+9]
                };

                if(sample.glucose_mmol>=10){
                  blink_blue();
                }else if(sample.glucose_mmol<4){
                  blink_red();
                }else{
                  stop_blinking();
                }

                Serial.printf("Glucose: %f Sample Age:%d Phase:%d \n",sample.glucose_mmol,sample.sample_age,sample.phase);
                    Serial.printf("\n");
              
            }
            return;
        }

    }
} scanCallbacks;
void Blink_green(void * pvParameters )
{
 while(1){ 
    NeoPixel.clear();
    NeoPixel.setPixelColor(0, NeoPixel.Color(0,255, 0));
    NeoPixel.show();
    delay(500);
    NeoPixel.setPixelColor(0, NeoPixel.Color(0, 0, 0));
    NeoPixel.show();
     delay(500);
  }
}
void Blink_red(void * pvParameters )
{
 while(1){ 
    NeoPixel.clear();
    NeoPixel.setPixelColor(0, NeoPixel.Color(255,0, 0));
    NeoPixel.show();
    delay(500);
    NeoPixel.setPixelColor(0, NeoPixel.Color(0, 0, 0));
    NeoPixel.show();
     delay(500);
  }
}
void Blink_blue(void * pvParameters )
{
 while(1){ 
    NeoPixel.clear();
    NeoPixel.setPixelColor(0, NeoPixel.Color(0,0, 255));
    NeoPixel.show();
    delay(500);
    NeoPixel.setPixelColor(0, NeoPixel.Color(0, 0, 0));
    NeoPixel.show();
     delay(500);
  }
}
void stop_blinking(){
    NeoPixel.setPixelColor(0, NeoPixel.Color(0, 0, 0));
    NeoPixel.show();
    vTaskSuspend(blink_red_);
    vTaskSuspend(blink_blue_);
     vTaskSuspend(blink_green_);
}
void blink_blue(){
  if(last_code!=1){
  stop_blinking();
  vTaskResume(blink_blue_);
  last_code=1;
  }
}
void blink_red(){
    if(last_code!=2){
  stop_blinking();
  vTaskResume(blink_red_);
  last_code=2;
  }
}
void blink_green(){
    if(last_code!=3){
  stop_blinking();
  vTaskResume(blink_green_);
  last_code=3;
  }
}
void setup() {
    Serial.begin(115200);
    Serial.println("Scanning...");

    NimBLEDevice::init("Beacon-scanner");
    pBLEScan = BLEDevice::getScan();
    pBLEScan->setScanCallbacks(&scanCallbacks);
    pBLEScan->setActiveScan(true);
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(100);
    NeoPixel.begin(); 

      xTaskCreatePinnedToCore(
                    Blink_red,   /* Task function. */
                    "blinkr",     /* name of task. */
                    10000,       /* Stack size of task */
                    NULL,        /* parameter of the task */
                    1,           /* priority of the task */
                    &blink_red_,      /* Task handle to keep track of created task */
                    1);          /* pin task to core 0 */ 
        xTaskCreatePinnedToCore(
                    Blink_blue,   /* Task function. */
                    "blinkb",     /* name of task. */
                    10000,       /* Stack size of task */
                    NULL,        /* parameter of the task */
                    1,           /* priority of the task */
                    &blink_blue_,      /* Task handle to keep track of created task */
                    1);          /* pin task to core 0 */ 
            xTaskCreatePinnedToCore(
                    Blink_green,   /* Task function. */
                    "blinkg",     /* name of task. */
                    10000,       /* Stack size of task */
                    NULL,        /* parameter of the task */
                    1,           /* priority of the task */
                    &blink_green_,      /* Task handle to keep track of created task */
                    1);          /* pin task to core 0 */ 
 blink_green();
                
}

void loop() {
    NimBLEScanResults foundDevices = pBLEScan->getResults(scanTime, false);
    Serial.print("Devices found: ");
    Serial.println(foundDevices.getCount());
    Serial.println("Scan done!");
    pBLEScan->clearResults(); // delete results scan buffer to release memory

    delay(2000);
}