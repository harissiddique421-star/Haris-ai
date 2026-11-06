package com.myraa.mobile;

/**
 * Prayer times and Qibla direction, calculated on the phone from latitude and longitude (no internet).
 * Method: University of Islamic Sciences, Karachi (Fajr and Isha at 18 degrees), Asr Hanafi by default
 * (shadow twice the object), as commonly used in Pakistan. Results are minutes after local midnight.
 */
final class PrayerCalc {
  private PrayerCalc(){}

  static final String[] NAMES={"Fajr","Sunrise","Dhuhr","Asr","Maghrib","Isha"};
  static final double KAABA_LAT=21.4225,KAABA_LON=39.8262;

  private static double rad(double d){return d*Math.PI/180;}
  private static double deg(double r){return r*180/Math.PI;}
  private static double fix(double a,double b){a=a-b*Math.floor(a/b);return a<0?a+b:a;}

  private static double julian(int year,int month,int day){
    if(month<=2){year-=1;month+=12;}
    double a=Math.floor(year/100.0),b=2-a+Math.floor(a/4);
    return Math.floor(365.25*(year+4716))+Math.floor(30.6001*(month+1))+day+b-1524.5;
  }
  /** Declination of the sun and equation of time for a Julian date. */
  private static double[] sun(double jd){
    double d=jd-2451545.0;
    double g=fix(357.529+0.98560028*d,360),q=fix(280.459+0.98564736*d,360);
    double l=fix(q+1.915*Math.sin(rad(g))+0.020*Math.sin(rad(2*g)),360);
    double e=23.439-0.00000036*d;
    double ra=fix(deg(Math.atan2(Math.cos(rad(e))*Math.sin(rad(l)),Math.cos(rad(l))))/15,24);
    double eqt=q/15-ra;
    double decl=deg(Math.asin(Math.sin(rad(e))*Math.sin(rad(l))));
    return new double[]{decl,eqt};
  }

  /**
   * Today's times in minutes after local midnight, in the order of NAMES, or NaN where the sun never
   * reaches the angle (far north or south).
   * @param tzHours the local offset from UTC for that date, in hours (5 for Pakistan)
   * @param hanafi true for the Hanafi Asr (used in Pakistan), false for the Shafi'i Asr
   */
  static double[] times(int year,int month,int day,double lat,double lon,double tzHours,boolean hanafi){
    double jd=julian(year,month,day)-lon/(15*24);
    double[] guess={5,6,12,13,18,18.5}; // hours
    double[] t=new double[6];
    for(int pass=0;pass<2;pass++){
      double[] g=pass==0?guess:t.clone();
      t[0]=angleTime(jd,g[0]/24,lat,18,true);
      t[1]=angleTime(jd,g[1]/24,lat,0.833,true);
      t[2]=midDay(jd,g[2]/24);
      t[3]=asr(jd,g[3]/24,lat,hanafi?2:1);
      t[4]=angleTime(jd,g[4]/24,lat,0.833,false);
      t[5]=angleTime(jd,g[5]/24,lat,18,false);
    }
    double[] out=new double[6];
    for(int i=0;i<6;i++){
      double h=t[i]+tzHours-lon/15;
      // A minute after the calculated moment for Dhuhr (the sun has passed its highest point) and Maghrib.
      if(i==2||i==4)h+=1.0/60;
      out[i]=Double.isNaN(h)?Double.NaN:fix(h,24)*60;
    }
    return out;
  }
  private static double midDay(double jd,double t){return fix(12-sun(jd+t)[1],24);}
  private static double angleTime(double jd,double t,double lat,double angle,boolean beforeNoon){
    double decl=sun(jd+t)[0],noon=midDay(jd,t);
    double c=(-Math.sin(rad(angle))-Math.sin(rad(decl))*Math.sin(rad(lat)))/(Math.cos(rad(decl))*Math.cos(rad(lat)));
    if(c<-1||c>1)return Double.NaN;
    double v=deg(Math.acos(c))/15;
    return noon+(beforeNoon?-v:v);
  }
  private static double asr(double jd,double t,double lat,int factor){
    double decl=sun(jd+t)[0];
    double angle=-deg(Math.atan(1/(factor+Math.tan(rad(Math.abs(lat-decl))))));
    return angleTime(jd,t,lat,angle,false);
  }

  /** Direction of the Kaaba from here, in degrees clockwise from true north. */
  static double qibla(double lat,double lon){
    double p1=rad(lat),p2=rad(KAABA_LAT),dl=rad(KAABA_LON-lon);
    return fix(deg(Math.atan2(Math.sin(dl),Math.cos(p1)*Math.tan(p2)-Math.sin(p1)*Math.cos(dl))),360);
  }
  /** "west, a little to the north" style words for a compass bearing. */
  static String compass(double bearing){
    String[] dirs={"north","north-east","east","south-east","south","south-west","west","north-west"};
    return dirs[(int)Math.round(fix(bearing,360)/45)%8];
  }
  static String clock(double minutes){
    if(Double.isNaN(minutes))return "-";
    int m=(int)Math.round(minutes)%(24*60),h=m/60,mm=m%60;
    return String.format(java.util.Locale.ROOT,"%d:%02d %s",h%12==0?12:h%12,mm,h<12?"AM":"PM");
  }
}
